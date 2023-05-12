package org.sunbird.learner.actors.coursebatch.dao.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.CassandraUtil;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.request.RequestContext;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.actors.coursebatch.dao.BatchUserDao;
import org.sunbird.learner.util.Util;
import org.sunbird.models.batch.user.BatchUser;
import org.sunbird.common.models.util.LoggerUtil;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BatchUserDaoImpl implements BatchUserDao{
    protected LoggerUtil logger = new LoggerUtil(this.getClass()); 
    private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    private ObjectMapper mapper = new ObjectMapper();
    static BatchUserDao batchUserDao;
    private static final String KEYSPACE_NAME =
            Util.dbInfoMap.get(JsonKey.ENROLLMENT_BATCH_DB).getKeySpace();

    private static final String ENROLLMENT_BATCH = 
            Util.dbInfoMap.get(JsonKey.ENROLLMENT_BATCH_DB).getTableName();
    public static BatchUserDao getInstance() {
        if (batchUserDao == null) {
            batchUserDao = new BatchUserDaoImpl();
        }
        return batchUserDao;
    }

    @Override
    public BatchUser readById(RequestContext requestContext, String batchId) {
        Map<String, Object> primaryKey = new HashMap<>();
        primaryKey.put(JsonKey.BATCH_ID, batchId);
        Response response = cassandraOperation.getRecordByIdentifier(requestContext, KEYSPACE_NAME, ENROLLMENT_BATCH, primaryKey, null);
        try {
            List<Map<String, Object>> batchUserList =
                (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
            if (CollectionUtils.isEmpty(batchUserList)) {
                return null;
            }
            return mapper.convertValue((Map<String, Object>) batchUserList.get(0), BatchUser.class);
        } catch (Exception e) {
            logger.error(requestContext, "Failed to read BatchUser Table. Exception: ", e);
            return null;
        }
    }

    @Override
    public BatchUser read(RequestContext requestContext, String batchId, String userId) {
        Map<String, Object> primaryKey = new HashMap<>();
        primaryKey.put(JsonKey.BATCH_ID, batchId);
        primaryKey.put(JsonKey.USER_ID, userId);
        Response response = cassandraOperation.getRecordByIdentifier(requestContext, KEYSPACE_NAME, ENROLLMENT_BATCH, primaryKey, null);
        try {
            List<Map<String, Object>> batchUserList =
                (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
            if (CollectionUtils.isEmpty(batchUserList)) {
                return null;
            }
            return mapper.readValue(mapper.writeValueAsString(batchUserList.get(0)), new TypeReference<BatchUser>() {});
        } catch (Exception e) {
            logger.error(requestContext, "Failed to read BatchUser Table. Exception: ", e);
            return null;
        }
    }
    @Override
    public Response insertBatchLookupRecord(RequestContext requestContext, Map<String, Object> userCoursesDetails) {
        return cassandraOperation.insertRecord(requestContext, KEYSPACE_NAME, ENROLLMENT_BATCH, userCoursesDetails);
    }


    @Override
    public Response updateBatchLookupRecord(RequestContext requestContext, String batchId, String userId, Map<String, Object> map) {
        Map<String, Object> primaryKey = new HashMap<>();
        primaryKey.put(JsonKey.BATCH_ID, batchId);
        primaryKey.put(JsonKey.USER_ID, userId);
        primaryKey.put(JsonKey.ENROLLED_DATE, map.get("enrolled_date"));
        Map<String, Object> attributeMap = new HashMap<>();
        attributeMap.putAll(map);
        attributeMap.remove(JsonKey.BATCH_ID);
        attributeMap.remove(JsonKey.USER_ID);
        attributeMap.remove(JsonKey.ENROLLED_DATE);
        attributeMap = CassandraUtil.changeCassandraColumnMapping(attributeMap);
        return cassandraOperation.updateRecord(
                requestContext, KEYSPACE_NAME, ENROLLMENT_BATCH, attributeMap, primaryKey);
    }
}




