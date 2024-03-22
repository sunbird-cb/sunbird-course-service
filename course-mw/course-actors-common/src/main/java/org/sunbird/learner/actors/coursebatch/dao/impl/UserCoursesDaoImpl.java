package org.sunbird.learner.actors.coursebatch.dao.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.logstash.logback.encoder.org.apache.commons.lang3.StringUtils;

import java.util.*;
import org.apache.commons.collections.CollectionUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.Constants;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.request.RequestContext;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.actors.coursebatch.dao.UserCoursesDao;
import org.sunbird.learner.util.Util;
import org.sunbird.models.user.courses.UserCourses;
import org.sunbird.common.models.util.LoggerUtil;

public class UserCoursesDaoImpl implements UserCoursesDao {
  protected LoggerUtil logger = new LoggerUtil(this.getClass()); 
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private ObjectMapper mapper = new ObjectMapper();
  static UserCoursesDao userCoursesDao;
  private static final String KEYSPACE_NAME =
      Util.dbInfoMap.get(JsonKey.LEARNER_COURSE_DB).getKeySpace();
  private static final String TABLE_NAME =
      Util.dbInfoMap.get(JsonKey.LEARNER_COURSE_DB).getTableName();
  private static final String USER_ENROLMENTS = Util.dbInfoMap.get(JsonKey.USER_ENROLMENTS_DB).getTableName();
  private static final String ENROLMENT_BATCH_LOOKUP = Util.dbInfoMap.get(JsonKey.ENROLLMENT_BATCH_DB).getTableName();
  public static UserCoursesDao getInstance() {
    if (userCoursesDao == null) {
      userCoursesDao = new UserCoursesDaoImpl();
    }
    return userCoursesDao;
  }
  
  @Override
  public UserCourses read(RequestContext requestContext, String batchId, String userId) {
    Map<String, Object> primaryKey = new HashMap<>();
    primaryKey.put(JsonKey.BATCH_ID, batchId);
    primaryKey.put(JsonKey.USER_ID, userId);
    Response response = cassandraOperation.getRecordByIdentifier(requestContext, KEYSPACE_NAME, TABLE_NAME, primaryKey, null);
    List<Map<String, Object>> userCoursesList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isEmpty(userCoursesList)) {
      return null;
    }
    try {
      return mapper.convertValue((Map<String, Object>) userCoursesList.get(0), UserCourses.class);
    } catch (Exception e) {
      logger.error(requestContext, "Failed to read user enrollment table. Exception: ", e);
    }
    return null;
  }


  @Override
  public Response update(RequestContext requestContext, String batchId, String userId, Map<String, Object> updateAttributes) {
    Map<String, Object> primaryKey = new HashMap<>();
    primaryKey.put(JsonKey.BATCH_ID, batchId);
    primaryKey.put(JsonKey.USER_ID, userId);
    Map<String, Object> updateList = new HashMap<>();
    updateList.putAll(updateAttributes);
    updateList.remove(JsonKey.BATCH_ID);
    updateList.remove(JsonKey.USER_ID);
    return cassandraOperation.updateRecord(requestContext, KEYSPACE_NAME, TABLE_NAME, updateList, primaryKey);
  }

  @Override
  public List<String> getAllActiveUserOfBatch(RequestContext requestContext, String batchId) {
    return getBatchParticipants(requestContext, batchId, true);
  }

  @Override
  public Response batchInsert(RequestContext requestContext, List<Map<String, Object>> userCoursesDetails) {
    return cassandraOperation.batchInsert(requestContext, KEYSPACE_NAME, USER_ENROLMENTS, userCoursesDetails);
  }

  @Override
  public Response insert(RequestContext requestContext, Map<String, Object> userCoursesDetails) {
    return cassandraOperation.insertRecord(requestContext, KEYSPACE_NAME, TABLE_NAME, userCoursesDetails);
  }

  @Override
  public Response insertV2(RequestContext requestContext, Map<String, Object> userCoursesDetails) {
    return cassandraOperation.insertRecord(requestContext, KEYSPACE_NAME, USER_ENROLMENTS, userCoursesDetails);
  }

  @Override
  public Response updateV2(RequestContext requestContext, String userId, String courseId, String batchId, Map<String, Object> updateAttributes) {
    Map<String, Object> primaryKey = new HashMap<>();
    primaryKey.put(JsonKey.USER_ID, userId);
    primaryKey.put(JsonKey.COURSE_ID, courseId);
    primaryKey.put(JsonKey.BATCH_ID, batchId);
    Map<String, Object> updateList = new HashMap<>();
    updateList.putAll(updateAttributes);
    updateList.remove(JsonKey.BATCH_ID_KEY);
    updateList.remove(JsonKey.COURSE_ID_KEY);
    updateList.remove(JsonKey.USER_ID_KEY);
    return cassandraOperation.updateRecord(requestContext, KEYSPACE_NAME, USER_ENROLMENTS, updateList, primaryKey);
  }

  @Override
  public UserCourses read(RequestContext requestContext, String userId, String courseId, String batchId) {
    Map<String, Object> primaryKey = new HashMap<>();
    primaryKey.put(JsonKey.USER_ID, userId);
    primaryKey.put(JsonKey.COURSE_ID, courseId);
    primaryKey.put(JsonKey.BATCH_ID, batchId);
    Response response = cassandraOperation.getRecordByIdentifier(requestContext, KEYSPACE_NAME, USER_ENROLMENTS, primaryKey, null);
    List<Map<String, Object>> userCoursesList =
            (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isEmpty(userCoursesList)) {
      return null;
    }
    try {
      return mapper.convertValue((Map<String, Object>) userCoursesList.get(0), UserCourses.class);
    } catch (Exception e) {
      logger.error(requestContext, "Failed to read user enrollments table. Exception: ", e);
    }
    return null;
  }

  @Override
  public List<String> getBatchParticipants(RequestContext requestContext, String batchId, boolean active) {
    Map<String, Object> queryMap = new HashMap<>();
    queryMap.put(JsonKey.BATCH_ID, batchId);
    Response response =
            cassandraOperation.getRecordByIdentifier(requestContext,KEYSPACE_NAME, ENROLMENT_BATCH_LOOKUP, queryMap, null);
        /*cassandraOperation.getRecords(
                requestContext, KEYSPACE_NAME, USER_ENROLMENTS, queryMap, Arrays.asList(JsonKey.USER_ID, JsonKey.ACTIVE));*/
    List<Map<String, Object>> userCoursesList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isEmpty(userCoursesList)) {
      return null;
    }
    List<String> userList = new ArrayList<String>();
    for(Map<String, Object> userCourse : userCoursesList) {
      if(userCourse.get(JsonKey.ACTIVE) != null 
        && (active == (boolean) userCourse.get(JsonKey.ACTIVE))) {
        userList.add((String) userCourse.get(JsonKey.USER_ID));
      }
    }
    return userList;
  }

  @Override
  public List<Map<String, Object>> listEnrolments(RequestContext requestContext, String userId, List<String> courseIdList) {
    Map<String, Object> primaryKey = new HashMap<>();
    primaryKey.put(JsonKey.USER_ID, userId);
    if(!CollectionUtils.isEmpty(courseIdList)){
      primaryKey.put(JsonKey.COURSE_ID_KEY, courseIdList);
    }
    Response response = cassandraOperation.getRecordByIdentifier(requestContext, KEYSPACE_NAME, USER_ENROLMENTS, primaryKey, null);
    List<Map<String, Object>> userCoursesList = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isEmpty(userCoursesList)) {
      return null;
    } else {
      return userCoursesList;
    }
  }

  @Override
  public Map<String, Object> getBatchParticipantsByPage(RequestContext requestContext, Map<String, Object> request) {
    logger.info(requestContext, "UserCourseDao:: getBatchParticipantsByPage:: Received request:: " + request);
    Map<String, Object> queryMap = new HashMap<>();
    queryMap.put(JsonKey.BATCH_ID, (String) request.get(JsonKey.BATCH_ID));
    Map<String, Object> result = new HashMap<String, Object>();
    List<String> userList = new ArrayList<String>();
    Boolean active = (Boolean) request.get(JsonKey.ACTIVE);
    if (null == active) {
      active = true;
    }
    Integer limit = (Integer) request.get(JsonKey.LIMIT);
    if (limit == null) {
      limit = Constants.DEFAULT_LIMIT;
    }
    Integer currentOffSetFromRequest = (Integer) request.get(JsonKey.CURRENT_OFFSET);
    if (currentOffSetFromRequest == null) {
      currentOffSetFromRequest = 0;
    }
    String pageId = (String) request.get(JsonKey.PAGE_ID);
    String previousPageId = null;
    int currentOffSet = 1;
    String currentPagingState = null;
    do {
      Response response = cassandraOperation.getRecordByIdentifierWithPage(requestContext, KEYSPACE_NAME,
          ENROLMENT_BATCH_LOOKUP, queryMap,
          null, pageId, (Integer) request.get(JsonKey.LIMIT));
      currentPagingState = (String) response.get(JsonKey.PAGE_ID);
      if (StringUtils.isBlank(previousPageId) && StringUtils.isNotBlank(currentPagingState)) {
        previousPageId = currentPagingState;
      }
      pageId = currentPagingState;
      List<Map<String, Object>> userCoursesList = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (CollectionUtils.isEmpty(userCoursesList)) {
        //Set null so that client knows there are no data to read further.
        previousPageId = null;
        break;
      }
      for (Map<String, Object> userCourse : userCoursesList) {
        //From this page, we have already read some records, so skip the records
        if (currentOffSetFromRequest > 0) {
          currentOffSetFromRequest--;
          continue;
        }
        if (userCourse.get(JsonKey.ACTIVE) != null
            && (active == (boolean) userCourse.get(JsonKey.ACTIVE))) {
          userList.add((String) userCourse.get(JsonKey.USER_ID));
          if (userList.size() == limit) {
            //We have read the data... if pageId available send back in response.
            previousPageId = pageId != null ? pageId : previousPageId;
            break;
          }
        }
        currentOffSet++;
      }
      //We may have read the given limit... if so, break from while loop
      if (userList.size() == limit) {
        break;
      }
    } while (StringUtils.isNotBlank(currentPagingState));
    if (StringUtils.isNotBlank(previousPageId)) {
      result.put(JsonKey.PAGE_ID, previousPageId);
      if (currentOffSet >= limit) {
        currentOffSet = currentOffSet - limit;
      }
      result.put(JsonKey.CURRENT_OFFSET, currentOffSet);
    }
    result.put(JsonKey.COUNT, userList.size());
    result.put(JsonKey.PARTICIPANTS, userList);
    return result;
  }

  public List<UserCourses> readAll(RequestContext requestContext, String userId, String courseId) {
    Map<String, Object> primaryKey = new HashMap<>();
    primaryKey.put(JsonKey.USER_ID, userId);
    primaryKey.put(JsonKey.COURSE_ID, courseId);
    Response response = cassandraOperation.getRecordByIdentifier(requestContext, KEYSPACE_NAME, USER_ENROLMENTS, primaryKey, null);
    List<Map<String, Object>> userCoursesList =
            (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isEmpty(userCoursesList)) {
      return null;
    }
    try {
      List<UserCourses> convertedUserCoursesList = new ArrayList<>();
      for (Object userCourseObj : userCoursesList) {
        if (userCourseObj instanceof Map) {
          Map<String, Object> userCourseMap = (Map<String, Object>) userCourseObj;
          UserCourses userCourses = mapper.convertValue(userCourseMap, UserCourses.class);
          convertedUserCoursesList.add(userCourses);
        }
      }
      return convertedUserCoursesList;
    } catch (Exception e) {
      logger.error(requestContext, "Failed to read user enrollments table. Exception: ", e);
    }
    return null;
  }
}