package controllers.courseenrollment;

import akka.actor.ActorRef;
import controllers.BaseController;
import controllers.courseenrollment.validator.CourseEnrollmentRequestValidator;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.Request;
import org.sunbird.learner.util.Util;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public class CourseEnrollmentController extends BaseController {

  @Inject
  @Named("course-enrolment-actor")
  private ActorRef courseEnrolmentActor;

  private CourseEnrollmentRequestValidator validator = new CourseEnrollmentRequestValidator();

  public CompletionStage<Result> getEnrolledCourses(String uid, Http.Request httpRequest,String version) {
      return handleRequest(courseEnrolmentActor, "listEnrol",
          httpRequest.body().asJson(),
          (req) -> {
              Request request = (Request) req;
              Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
              if(queryParams.containsKey("fields")) {
                  Set<String> fields = new HashSet<>(Arrays.asList(queryParams.get("fields")[0].split(",")));
                  fields.addAll(Arrays.asList(JsonKey.NAME, JsonKey.DESCRIPTION, JsonKey.LEAF_NODE_COUNT, JsonKey.APP_ICON));
                  queryParams.put("fields", fields.toArray(new String[0]));
              }
              String userId = (String) request.getContext().getOrDefault(JsonKey.REQUESTED_FOR, request.getContext().get(JsonKey.REQUESTED_BY));
              validator.validateRequestedBy(userId);
              request.getContext().put(JsonKey.USER_ID, userId);
              request.getRequest().put(JsonKey.USER_ID, userId);
              request.getContext().put("version", version);

              request
                  .getContext()
                  .put(JsonKey.URL_QUERY_STRING, getQueryString(queryParams));
              request
                  .getContext()
                  .put(JsonKey.BATCH_DETAILS, httpRequest.queryString().get(JsonKey.BATCH_DETAILS));
              if (queryParams.containsKey("cache")) {
                  request.getContext().put("cache", Boolean.parseBoolean(queryParams.get("cache")[0]));
              } else
                  request.getContext().put("cache", true);
              return null;
          },
          null,
          null,
          getAllRequestHeaders((httpRequest)),
          false,
          httpRequest);
  }


    public CompletionStage<Result> privateGetEnrolledCourses(String uid, Http.Request httpRequest) {
        return handleRequest(courseEnrolmentActor, "listEnrol",
            httpRequest.body().asJson(),
            (req) -> {
                Request request = (Request) req;
                Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                if(queryParams.containsKey("fields")) {
                    Set<String> fields = new HashSet<>(Arrays.asList(queryParams.get("fields")[0].split(",")));
                    fields.addAll(Arrays.asList(JsonKey.NAME, JsonKey.DESCRIPTION, JsonKey.LEAF_NODE_COUNT, JsonKey.APP_ICON));
                    queryParams.put("fields", fields.toArray(new String[0]));
                }

                request
                    .getContext()
                    .put(JsonKey.URL_QUERY_STRING, getQueryString(queryParams));
                request
                    .getContext()
                    .put(JsonKey.BATCH_DETAILS, httpRequest.queryString().get(JsonKey.BATCH_DETAILS));
                if (queryParams.containsKey("cache")) {
                    request.getContext().put("cache", Boolean.parseBoolean(queryParams.get("cache")[0]));
                } else
                    request.getContext().put("cache", true);
                return null;
            },
            ProjectUtil.getLmsUserId(uid),
            JsonKey.USER_ID,
            getAllRequestHeaders((httpRequest)),
            false,
            httpRequest);
    }

  public CompletionStage<Result> enrollCourse(Http.Request httpRequest) {
    return handleRequest(courseEnrolmentActor, "enrol",
        httpRequest.body().asJson(),
        (request) -> {
          Request req = (Request) request;
          Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
          String courseId = req.getRequest().containsKey(JsonKey.COURSE_ID) ? JsonKey.COURSE_ID : JsonKey.COLLECTION_ID;
          String batchId = (String)req.getRequest().get(JsonKey.BATCH_ID);
          req.getRequest().put(JsonKey.COURSE_ID, req.getRequest().get(courseId));
          String userId = (String) req.getContext().getOrDefault(JsonKey.REQUESTED_FOR, req.getContext().get(JsonKey.REQUESTED_BY));
          validator.validateRequestedBy(userId);
          logger.info( ((Request) request).getRequestContext(), " CourseEnrollmentController : Request for enroll recieved, UserId : "+  userId +", courseId : "+courseId + ", batchId:"+batchId);
          req.getRequest().put(JsonKey.USER_ID, userId);
          validator.validateEnrollCourse(req);
          return null;
        },
        getAllRequestHeaders(httpRequest),
        httpRequest);
  }
  
  public CompletionStage<Result> unenrollCourse(Http.Request httpRequest) {
    return handleRequest(
            courseEnrolmentActor, "unenrol",
        httpRequest.body().asJson(),
        (request) -> {
          Request req = (Request) request;
          Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
          String courseId = req.getRequest().containsKey(JsonKey.COURSE_ID) ? JsonKey.COURSE_ID : JsonKey.COLLECTION_ID;
          String batchId = (String)req.getRequest().get(JsonKey.BATCH_ID);
            req.getRequest().put(JsonKey.COURSE_ID, req.getRequest().get(courseId));
          String userId = (String) req.getContext().getOrDefault(JsonKey.REQUESTED_FOR, req.getContext().get(JsonKey.REQUESTED_BY));
          validator.validateRequestedBy(userId);
          logger.info( ((Request) request).getRequestContext(), " CourseEnrollmentController : Request for un-enroll recieved, UserId : "+  userId +", courseId : "+courseId+ ", batchId:"+batchId);
          req.getRequest().put(JsonKey.USER_ID, userId);
          validator.validateUnenrollCourse(req);
          return null;
        },
        getAllRequestHeaders(httpRequest),
        httpRequest);
  }

    public CompletionStage<Result> getUserEnrolledCourses(Http.Request httpRequest) {
        return handleRequest(
                courseEnrolmentActor, "listEnrol",
                httpRequest.body().asJson(),
                (req) -> {
                    Request request = (Request) req;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    if(queryParams.containsKey("fields")) {
                        Set<String> fields = new HashSet<>(Arrays.asList(queryParams.get("fields")[0].split(",")));
                        fields.addAll(Arrays.asList(JsonKey.NAME, JsonKey.DESCRIPTION, JsonKey.LEAF_NODE_COUNT, JsonKey.APP_ICON));
                        queryParams.put("fields", fields.toArray(new String[0]));
                    }
                    request
                            .getContext()
                            .put(JsonKey.URL_QUERY_STRING, getQueryString(queryParams));
                    request
                            .getContext()
                            .put(JsonKey.BATCH_DETAILS, httpRequest.queryString().get(JsonKey.BATCH_DETAILS));
                    String userId = (String) request.getContext().getOrDefault(JsonKey.REQUESTED_FOR, request.getContext().get(JsonKey.REQUESTED_BY));
                    validator.validateRequestedBy(userId);
                    request.getContext().put(JsonKey.USER_ID, userId);
                    request.getRequest().put(JsonKey.USER_ID, userId);
                    return null;
                },
                getAllRequestHeaders((httpRequest)),
                httpRequest);
    }


    public CompletionStage<Result> privateGetUserEnrolledCourses(Http.Request httpRequest) {
        return handleRequest(
            courseEnrolmentActor, "listEnrol",
            httpRequest.body().asJson(),
            (req) -> {
                Request request = (Request) req;
                Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                if(queryParams.containsKey("fields")) {
                    Set<String> fields = new HashSet<>(Arrays.asList(queryParams.get("fields")[0].split(",")));
                    fields.addAll(Arrays.asList(JsonKey.NAME, JsonKey.DESCRIPTION, JsonKey.LEAF_NODE_COUNT, JsonKey.APP_ICON));
                    queryParams.put("fields", fields.toArray(new String[0]));
                }
                request
                    .getContext()
                    .put(JsonKey.URL_QUERY_STRING, getQueryString(queryParams));
                request
                    .getContext()
                    .put(JsonKey.BATCH_DETAILS, httpRequest.queryString().get(JsonKey.BATCH_DETAILS));
                validator.validateUserEnrolledCourse(request);
                request.getContext().put(JsonKey.USER_ID, request.get(JsonKey.USER_ID));
                request.getRequest().put(JsonKey.USER_ID, request.get(JsonKey.USER_ID));
                return null;
            },
            getAllRequestHeaders((httpRequest)),
            httpRequest);
    }

    public CompletionStage<Result> adminEnrollCourse(Http.Request httpRequest) {
        return handleRequest(courseEnrolmentActor, "enrol",
                httpRequest.body().asJson(),
                (request) -> {
                    Request req = (Request) request;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    String courseId = req.getRequest().containsKey(JsonKey.COURSE_ID) ? JsonKey.COURSE_ID : JsonKey.COLLECTION_ID;
                    req.getRequest().put(JsonKey.COURSE_ID, req.getRequest().get(courseId));
                    validator.validateEnrollCourse(req);
                    return null;
                },
                getAllRequestHeaders(httpRequest),
                httpRequest);
    }

    public CompletionStage<Result> adminUnenrollCourse(Http.Request httpRequest) {
        return handleRequest(
                courseEnrolmentActor, "unenrol",
                httpRequest.body().asJson(),
                (request) -> {
                    Request req = (Request) request;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    String courseId = req.getRequest().containsKey(JsonKey.COURSE_ID) ? JsonKey.COURSE_ID : JsonKey.COLLECTION_ID;
                    req.getRequest().put(JsonKey.COURSE_ID, req.getRequest().get(courseId));
                    validator.validateUnenrollCourse(req);
                    return null;
                },
                getAllRequestHeaders(httpRequest),
                httpRequest);
    }

    public CompletionStage<Result> getParticipantsForFixedBatch(Http.Request httpRequest) {
        return handleRequest(courseEnrolmentActor, "getParticipantsForFixedBatch",
                httpRequest.body().asJson(),
                (request) -> {
                    Util.handleFixedBatchIdRequest((Request) request);
                    new CourseEnrollmentRequestValidator().validateCourseParticipant((Request) request);
                    return null;
                },
                getAllRequestHeaders(httpRequest),
                httpRequest);
    }

    public CompletionStage<Result> adminGetUserEnrolledCourses(Http.Request httpRequest) {
        return handleRequest(
                courseEnrolmentActor, "listEnrol",
                httpRequest.body().asJson(),
                (req) -> {
                    Request request = (Request) req;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    if(queryParams.containsKey("fields")) {
                        Set<String> fields = new HashSet<>(Arrays.asList(queryParams.get("fields")[0].split(",")));
                        fields.addAll(Arrays.asList(JsonKey.NAME, JsonKey.DESCRIPTION, JsonKey.LEAF_NODE_COUNT, JsonKey.APP_ICON));
                        queryParams.put("fields", fields.toArray(new String[0]));
                    }
                    request
                            .getContext()
                            .put(JsonKey.URL_QUERY_STRING, getQueryString(queryParams));
                    request
                            .getContext()
                            .put(JsonKey.BATCH_DETAILS, httpRequest.queryString().get(JsonKey.BATCH_DETAILS));

                    return null;
                },
                getAllRequestHeaders((httpRequest)),
                httpRequest);
    }

    public CompletionStage<Result> getEnrolledCourses_v1(String uid, Http.Request httpRequest) {
        return getEnrolledCourses(uid,httpRequest,"v1");
    }
    public CompletionStage<Result> getEnrolledCourses_v2(String uid, Http.Request httpRequest) {
        return getEnrolledCourses(uid, httpRequest, "v2");
    }
    public CompletionStage<Result> enrollProgram(Http.Request httpRequest) {
        return handleRequest(courseEnrolmentActor, "enrolProgram",
                httpRequest.body().asJson(),
                (request) -> {
                    Request req = (Request) request;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    String programId = req.getRequest().containsKey(JsonKey.PROGRAM_ID) ? JsonKey.PROGRAM_ID : JsonKey.COLLECTION_ID;
                    req.getRequest().put(JsonKey.PROGRAM_ID, req.getRequest().get(programId));
                    String userId = (String) req.getContext().getOrDefault(JsonKey.REQUESTED_FOR, req.getContext().get(JsonKey.REQUESTED_BY));
                    req.getRequest().put(JsonKey.IS_ADMIN_API, false);
                    validator.validateRequestedBy(userId);
                    validator.validateEnrollProgram(req);
                    req.getRequest().put(JsonKey.USER_ID, userId);
                    return null;
                },
                getAllRequestHeaders(httpRequest),
                httpRequest);
    }

    public CompletionStage<Result> adminEnrollProgram(Http.Request httpRequest) {
        return handleRequest(courseEnrolmentActor, "enrolProgram",
                httpRequest.body().asJson(),
                (request) -> {
                    Request req = (Request) request;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    String programId = req.getRequest().containsKey(JsonKey.PROGRAM_ID) ? JsonKey.PROGRAM_ID : JsonKey.COLLECTION_ID;
                    req.getRequest().put(JsonKey.PROGRAM_ID, req.getRequest().get(programId));
                    req.getRequest().put(JsonKey.IS_ADMIN_API, true);
                    validator.validateEnrollProgram(req);
                    return null;
                },
                getAllRequestHeaders(httpRequest),
                httpRequest);
    }

    public CompletionStage<Result> adminBulkEnrollProgram(Http.Request httpRequest) {
        return handleRequest(courseEnrolmentActor, "bulkEnrolProgram",
                httpRequest.body().asJson(),
                (request) -> {
                    Request req = (Request) request;
                    Map<String, String[]> queryParams = new HashMap<>(httpRequest.queryString());
                    String programId = req.getRequest().containsKey(JsonKey.PROGRAM_ID) ? JsonKey.PROGRAM_ID : JsonKey.COLLECTION_ID;
                    req.getRequest().put(JsonKey.PROGRAM_ID, req.getRequest().get(programId));
                    req.getRequest().put(JsonKey.IS_ADMIN_API, true);
                    validator.bulkEnrollValidationsForProgram(req);
                    return null;
                },
                getAllRequestHeaders(httpRequest),
                httpRequest);
    }
}
