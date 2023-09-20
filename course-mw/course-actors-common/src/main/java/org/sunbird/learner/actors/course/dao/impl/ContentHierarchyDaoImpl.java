package org.sunbird.learner.actors.course.dao.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.Constants;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.request.RequestContext;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.actors.course.dao.ContentHierarchyDao;
import org.sunbird.learner.util.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ContentHierarchyDaoImpl implements ContentHierarchyDao {

    private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    private Util.DbInfo contentHierarchyDb = Util.dbInfoMap.get(JsonKey.CONTENT_HIERARCHY_STORE_DB);
    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<Map<String, Object>> getContentChildren(RequestContext requestContext, String programId) {
        Map<String, Object> primaryKey = new HashMap<>();
        primaryKey.put(JsonKey.IDENTIFIER, programId);
        Response programHierarchyResponse =
                cassandraOperation.getRecordByIdentifier(
                        requestContext, contentHierarchyDb.getKeySpace(), contentHierarchyDb.getTableName(), primaryKey, null);
        List<Map<String, Object>> response = (List<Map<String, Object>>) programHierarchyResponse.get(Constants.RESPONSE);
        Map<String, Object> childHierarchy = null;
        try {
            childHierarchy = mapper.readValue((String) response.get(0).get(JsonKey.HIERARCHY), new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return (List<Map<String, Object>>) childHierarchy.get(JsonKey.CHILDREN);
    }
}
