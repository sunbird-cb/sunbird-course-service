package org.sunbird.learner.actors.coursebatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.collections.CollectionUtils;
import org.sunbird.actor.base.BaseActor;
import org.sunbird.common.Constants;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerUtil;
import org.sunbird.common.models.util.PropertiesCache;
import org.sunbird.common.request.Request;
import org.sunbird.common.request.RequestContext;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.common.util.JsonUtil;
import org.sunbird.learner.actors.coursebatch.dao.BatchUserDao;
import org.sunbird.learner.actors.coursebatch.dao.impl.BatchUserDaoImpl;
import org.sunbird.learner.util.ContentUtil;
import org.sunbird.learner.util.CourseBatchSchedulerUtil;
import org.sunbird.models.batch.user.BatchUser;
import org.sunbird.models.course.batch.CourseBatch;
import org.sunbird.userorg.UserOrgService;
import org.sunbird.userorg.UserOrgServiceImpl;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Actor responsible to sending email notifications to participants and mentors in open and
 * invite-only batches.
 */
public class CourseBatchNotificationActor extends BaseActor {
  private static String courseBatchNotificationSignature =
      PropertiesCache.getInstance()
          .getProperty(JsonKey.SUNBIRD_COURSE_BATCH_NOTIFICATION_SIGNATURE);
  private static String baseUrl =
      PropertiesCache.getInstance().getProperty(JsonKey.SUNBIRD_WEB_URL);
  private static String contentBucket = PropertiesCache.getInstance().getProperty(JsonKey.CONTENT_BUCKET);
  private static String staticHostUrl = PropertiesCache.getInstance().getProperty(JsonKey.STATIC_HOST_URL);
  private static String profileUpdateUrl = PropertiesCache.getInstance().getProperty(JsonKey.PROFILE_UPDATE_URL);
  private static String courseBatchPath =
          PropertiesCache.getInstance().getProperty(JsonKey.COURSE_BATCH_PATH);
  private UserOrgService userOrgService = UserOrgServiceImpl.getInstance();
  private BatchUserDao batchUserDao = new BatchUserDaoImpl();
  private ObjectMapper mapper = new ObjectMapper();

  @Override
  public void onReceive(Request request) throws Throwable {
    String requestedOperation = request.getOperation();

    if (requestedOperation.equals(ActorOperations.COURSE_BATCH_NOTIFICATION.getValue())) {
      logger.info(request.getRequestContext(), "CourseBatchNotificationActor:onReceive: operation = " + request.getOperation());
      courseBatchNotification(request);
    } else if (requestedOperation.equals(ActorOperations.COURSE_BATCH_DATE_NOTIFICATION.getValue())) {
      courseBatchDatesUpdateNotification(request);
    } else {
      logger.error(request.getRequestContext(), "CourseBatchNotificationActor:onReceive: Unsupported operation = "
              + request.getOperation(), null);
    }
  }

  private void courseBatchNotification(Request request) throws Exception {

    Map<String, Object> requestMap = request.getRequest();

    CourseBatch courseBatch = (CourseBatch) requestMap.get(JsonKey.COURSE_BATCH);
    String authToken = (String) request.getContext().getOrDefault(JsonKey.X_AUTH_TOKEN, "");

    String userId = (String) requestMap.get(JsonKey.USER_ID);
    logger.info(request.getRequestContext(), "CourseBatchNotificationActor:courseBatchNotification: userId = " + userId);

    Map<String, String> headers = CourseBatchSchedulerUtil.headerMap;
    Map<String, Object> contentDetails =
        ContentUtil.getCourseObjectFromEkStep(courseBatch.getCourseId(), headers);

    if (userId != null) {
      logger.info(request.getRequestContext(), "CourseBatchNotificationActor:courseBatchNotification: Open batch");

      // Open batch
      String template = JsonKey.OPEN_BATCH_LEARNER_UNENROL;
      String subject = JsonKey.UNENROLL_FROM_COURSE_BATCH;

      String operationType = (String) requestMap.get(JsonKey.OPERATION_TYPE);

      if (operationType.equals(JsonKey.ADD)) {
        template = JsonKey.OPEN_BATCH_LEARNER_ENROL;
        subject = JsonKey.COURSE_INVITATION;
      }

      triggerEmailNotification( request.getRequestContext(), 
          Arrays.asList(userId), courseBatch, subject, template, contentDetails, authToken);

    } else {
      logger.info(request.getRequestContext(), "CourseBatchNotificationActor:courseBatchNotification: Invite only batch");

      List<String> addedMentors = (List<String>) requestMap.get(JsonKey.ADDED_MENTORS);
      List<String> removedMentors = (List<String>) requestMap.get(JsonKey.REMOVED_MENTORS);

      triggerEmailNotification(
              request.getRequestContext(), addedMentors,
          courseBatch,
          JsonKey.COURSE_INVITATION,
          JsonKey.BATCH_MENTOR_ENROL,
          contentDetails, authToken);
      triggerEmailNotification(
              request.getRequestContext(), removedMentors,
          courseBatch,
          JsonKey.UNENROLL_FROM_COURSE_BATCH,
          JsonKey.BATCH_MENTOR_UNENROL,
          contentDetails, authToken);

      List<String> addedParticipants = (List<String>) requestMap.get(JsonKey.ADDED_PARTICIPANTS);
      List<String> removedParticipants =
          (List<String>) requestMap.get(JsonKey.REMOVED_PARTICIPANTS);

      triggerEmailNotification(
              request.getRequestContext(), addedParticipants,
          courseBatch,
          JsonKey.COURSE_INVITATION,
          JsonKey.BATCH_LEARNER_ENROL,
          contentDetails, authToken);
      triggerEmailNotification(
              request.getRequestContext(), removedParticipants,
          courseBatch,
          JsonKey.UNENROLL_FROM_COURSE_BATCH,
          JsonKey.BATCH_LEARNER_UNENROL,
          contentDetails, authToken);
    }
  }

  private void triggerEmailNotification(
          RequestContext requestContext, List<String> userIdList,
          CourseBatch courseBatch,
          String subject,
          String template,
          Map<String, Object> contentDetails, String authToken) throws Exception {

    logger.debug(requestContext, "CourseBatchNotificationActor:triggerEmailNotification: userIdList = "
            + userIdList);

    if (CollectionUtils.isEmpty(userIdList)) return;

    for (String userId : userIdList) {
      Map<String, Object> requestMap =
          createEmailRequest(userId, courseBatch, contentDetails, subject, template);

      logger.info(requestContext, "CourseBatchNotificationActor:triggerEmailNotification: requestMap = " + requestMap);
      sendMail(requestContext, requestMap, authToken);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> createEmailRequest(
      String userId,
      CourseBatch courseBatch,
      Map<String, Object> contentDetails,
      String subject,
      String template) throws Exception {
    Map<String, Object> courseBatchObject = JsonUtil.convert(courseBatch, Map.class);

    Map<String, Object> request = new HashMap<>();
    Map<String, Object> requestMap = new HashMap<String, Object>();

    requestMap.put(JsonKey.SUBJECT, subject);
    requestMap.put(JsonKey.EMAIL_TEMPLATE_TYPE, template);
    requestMap.put(JsonKey.BODY, "Notification mail Body");
    requestMap.put(JsonKey.ORG_NAME, courseBatchObject.get(JsonKey.ORG_NAME));
    requestMap.put(JsonKey.COURSE_LOGO_URL, contentDetails.get(JsonKey.APP_ICON));
    if (contentDetails.containsKey(JsonKey.POSTER_IMAGE)) {
      String posterImageUrl = (String) contentDetails.get(JsonKey.POSTER_IMAGE);
      if (posterImageUrl.contains(staticHostUrl)) {
        String[] posterImageUrlArr = posterImageUrl.split("/content/");
        posterImageUrl = baseUrl + contentBucket + "/" + posterImageUrlArr[1];
      }
      requestMap.put(JsonKey.COURSE_POSTER_IMAGE, posterImageUrl);
    }
    requestMap.put(JsonKey.PROVIDER_NAME,contentDetails.get(JsonKey.SOURCE));
    requestMap.put(JsonKey.PROFILE_UPDATE_LINK, baseUrl+profileUpdateUrl);
    requestMap.put(JsonKey.START_DATE, courseBatchObject.get(JsonKey.START_DATE));
    requestMap.put(JsonKey.END_DATE, courseBatchObject.get(JsonKey.END_DATE));
    requestMap.put(JsonKey.COURSE_ID, courseBatchObject.get(JsonKey.COURSE_ID));
    requestMap.put(JsonKey.BATCH_NAME, courseBatch.getName());
    requestMap.put(JsonKey.COURSE_NAME, contentDetails.get(JsonKey.NAME));
    requestMap.put(
        JsonKey.COURSE_BATCH_URL,
        getCourseBatchUrl(courseBatch.getCourseId(), courseBatch.getBatchId()));
    requestMap.put(JsonKey.SIGNATURE, courseBatchNotificationSignature);
    requestMap.put(JsonKey.RECIPIENT_USERIDS, Arrays.asList(userId));
    request.put(JsonKey.REQUEST, requestMap);
    return request;
  }

  private String getCourseBatchUrl(String courseId, String batchId) {

    String url = baseUrl + "/learn/course/" + courseId + "/batch/" + batchId;
    return url;
  }

  private void sendMail(RequestContext requestContext, Map<String, Object> requestMap, String authToken) {
    logger.info(requestContext, "CourseBatchNotificationActor:sendMail: email ready");
    try {
      userOrgService.sendEmailNotification(requestMap, authToken);
      logger.info(requestContext, "CourseBatchNotificationActor:sendMail: Email sent successfully");
    } catch (Exception e) {
      logger.error(requestContext, "CourseBatchNotificationActor:sendMail: Exception occurred with error message = "
                      + e.getMessage(), e);
    }
  }

  private void courseBatchDatesUpdateNotification(Request request) throws UnirestException {
    RequestContext requestContext = request.getRequestContext();
    logger.info(requestContext,"Entered CourseBatchNotificationActor :: courseBatchDatesUpdateNotification Request Recieved : " + request);
    CourseBatch oldCourseBatch = (CourseBatch) request.getRequest().get(Constants.OLD_COURSE_BATCH);
    CourseBatch updatedCourseBatch = (CourseBatch) request.getRequest().get(Constants.UPDATED_COURSE_BATCH);

    Map<String, Object> requestBody = (Map<String, Object>) request.getRequest().get(Constants.REQUEST_BODY);
    Map<String, Object> batchAttributes = (Map<String, Object>) requestBody.get("batchAttributes");
    List<String> mentorsIds = (List<String>) requestBody.get("mentors");
    logger.info(requestContext,"Received Mentors from the Batch Update Request : " + mentorsIds);
    List<String> reciepientList = new ArrayList<>();
    reciepientList.addAll(mentorsIds);
    if (null != batchAttributes) {
      List<Map<String, Object>> sessionDetails = (List<Map<String, Object>>) batchAttributes.get("sessionDetails_v2");
      if (null != sessionDetails) {
        for (Map<String, Object> sessionDetail : sessionDetails) {
          List<String> facilitatorIds = (List<String>) sessionDetail.get("facilatorIDs");
          if(CollectionUtils.isNotEmpty(facilitatorIds)){
            reciepientList.addAll(facilitatorIds);
          }
          logger.info(requestContext,"Received facilitators from the Batch Update Request : "  + facilitatorIds);
        }
      }
    }
    List<BatchUser> batchUsers = batchUserDao.readById(requestContext, updatedCourseBatch.getBatchId());
    List<String> batchUserIdList = new ArrayList<>();
    if(null != batchUsers){
      for(BatchUser batchUser : batchUsers){
        if(null != batchUser.getActive() && batchUser.getActive()){
          batchUserIdList.add(batchUser.getUserId());
        }
      }
    }
    reciepientList.addAll(batchUserIdList);
    logger.info(requestContext,"Recieved Active Users from enrollment_batch_lookup : " + batchUserIdList);
    sendEmailNotificationMailForBatchDatesUpdate(requestContext,reciepientList, oldCourseBatch, updatedCourseBatch);
  }

  private void sendEmailNotificationMailForBatchDatesUpdate(RequestContext requestContext,List<String> reciepientList, CourseBatch oldBatch, CourseBatch updatedBatch) {
    Map<String, Object> request = new HashMap<>();
    request.put(Constants.COURSE_NAME, updatedBatch.getName());
    request.put(Constants.BATCH_NAME, updatedBatch.getDescription());
    request.put(Constants.RECIPIENT_IDS, reciepientList);
    request.put(JsonKey.SUBJECT, getBatchUpdateEmailSubject());
    request.put(Constants.TRAINING_NAME, updatedBatch.getName());
    request.put(JsonKey.REGARDS, Constants.KARMAYOGI_BHARAT);
    request.put(JsonKey.EMAIL_TEMPLATE_TYPE, Constants.BATCH_DATE_UPDATE_TEMPLATE);
    request.put(Constants.START_DATE, updatedBatch.getStartDate());
    request.put(Constants.END_DATE, updatedBatch.getEndDate());
    request.put(Constants.ENROLLMENT_END_DATE, updatedBatch.getEnrollmentEndDate());
    request.put(JsonKey.BODY, Constants.EMAIL_BODY);
    HashMap<String, String> headers = new HashMap<>();
    headers.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
    HashMap<String, Object> requestBody = new HashMap<>();
    requestBody.put(JsonKey.REQUEST, request);
    StringBuilder url = new StringBuilder();
    HttpResponse<String> httpResponse = null;
    String requeststr = null;
    try {
      requeststr = mapper.writeValueAsString(requestBody);
      url.append(getLearnerHost()).append(getLearnerPath());
      httpResponse = Unirest.post(String.valueOf(url)).headers(headers).body(requeststr).asString();
      if (httpResponse != null) {
        if(ResponseCode.OK.getResponseCode() != httpResponse.getStatus())
          throw new RuntimeException("An error occurred while sending mail notification, the response status is: " + httpResponse.getStatusText());
      } else{
        throw new RuntimeException("The Request could not be sent, response is : " + httpResponse);
      }
      logger.info(requestContext, "Notification sent successfully, response is : " + httpResponse.getStatusText());
    } catch (Exception e) {
      if (e instanceof RuntimeException && null != httpResponse) {
        logger.error(null, e.getMessage() + "Headers : " + httpResponse.getHeaders() + "Body : " + httpResponse.getBody(), e);
      }
      logger.error(null, "Exception occurred with error message = " + e.getMessage(), e);
    }
  }

  private String getLearnerHost() {
    return PropertiesCache.getInstance()
            .getProperty(JsonKey.LMS_SERVICE_HOST);
  }

  private String getLearnerPath() {
    return PropertiesCache.getInstance()
            .getProperty(JsonKey.LMS_SEND_EMAIL_NOTIFICATION_PATH);
  }

  private String getBatchUpdateEmailSubject() {
    return PropertiesCache.getInstance()
            .getProperty(JsonKey.SUNBIRD_BATCH_DATE_UPDATE_NOTIFICATIONS_SUBJECT);
  }
}

