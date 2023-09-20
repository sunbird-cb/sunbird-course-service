package org.sunbird.learner.actors.course.dao;

import org.sunbird.common.request.RequestContext;

import java.util.List;
import java.util.Map;

public interface ContentHierarchyDao {

    List<Map<String, Object>> getContentChildren(RequestContext requestContext, String programId);
}
