package org.sunbird.learner.actors.coursebatch.dao;
import java.security.Timestamp;
import java.util.List;
import java.util.Map;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.request.Request;
import org.sunbird.common.request.RequestContext;
import org.sunbird.models.batch.user.BatchUser;
import org.sunbird.models.course.batch.CourseBatch;
import org.sunbird.models.user.courses.UserCourses;

public interface BatchUserDao {
    /**
     * Get user courses information.
     *
     * @param batchId,userId user courses identifiers
     * @param requestContext
     * @return User batch  information
     */

    BatchUser readById(RequestContext requestContext, String batchId);
    BatchUser read(RequestContext requestContext, String batchId, String userId);
    Response insertBatchLookupRecord(RequestContext requestContext, Map<String, Object> userCoursesDetails);

    Response updateBatchLookupRecord(RequestContext requestContext, String courseId, String batchId, Map<String, Object> updateAttributes);


}
