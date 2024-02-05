package controllers.courseenrollment.validator;

import net.logstash.logback.encoder.org.apache.commons.lang3.ObjectUtils;
import org.jclouds.chef.util.CollectionUtils;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.util.*;
import org.sunbird.common.request.BaseRequestValidator;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;

import java.util.List;

public class CourseEnrollmentRequestValidator extends BaseRequestValidator {

  public CourseEnrollmentRequestValidator() {}

  public void validateEnrollCourse(Request courseRequestDto) {
    commonValidations(courseRequestDto);
  }

  public void validateEnrollProgram(Request programRequestDto) {
    commonValidationsProgram(programRequestDto);
  }
  public void validateUnenrollCourse(Request courseRequestDto) {
    commonValidations(courseRequestDto);
  }

  private void commonValidations(Request courseRequestDto) {
    validateParam(
        (String) courseRequestDto.getRequest().get(JsonKey.COURSE_ID),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.COURSE_ID+"/"+JsonKey.COLLECTION_ID);
    validateParam(
        (String) courseRequestDto.getRequest().get(JsonKey.BATCH_ID),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.BATCH_ID);
    validateParam(
        (String) courseRequestDto.getRequest().get(JsonKey.USER_ID),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.USER_ID);
  }

  public void validateEnrolledCourse(Request courseRequestDto) {
    validateParam(
        (String) courseRequestDto.getRequest().get(JsonKey.BATCH_ID),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.BATCH_ID);
    validateParam(
        (String) courseRequestDto.getRequest().get(JsonKey.USER_ID),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.USER_ID);
  }

  public void validateUserEnrolledCourse(Request courseRequestDto) {
    validateParam(
            (String) courseRequestDto.get(JsonKey.USER_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.USER_ID);
  }

  public void validateCourseParticipant(Request courseRequestDto) {
    validateParam(
            (String) courseRequestDto.getRequest().get(JsonKey.COURSE_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.COURSE_ID+"/"+JsonKey.ENROLLABLE_ITEM_ID+"/"+JsonKey.COLLECTION_ID);
    validateParam(
            (String) courseRequestDto.getRequest().get(JsonKey.BATCH_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.BATCH_ID+"/"+JsonKey.FIXED_BATCH_ID);
  }

  private void commonValidationsProgram(Request programRequestDto) {
    validateParam(
            (String) programRequestDto.getRequest().get(JsonKey.PROGRAM_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.PROGRAM_ID+"/"+JsonKey.COLLECTION_ID);
    validateParam(
            (String) programRequestDto.getRequest().get(JsonKey.BATCH_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.BATCH_ID);
    validateParam(
            (String) programRequestDto.getRequest().get(JsonKey.USER_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.USER_ID);
  }

  public void bulkEnrollValidationsForProgram(Request programRequestDto) {
    validateParam(
            (String) programRequestDto.getRequest().get(JsonKey.PROGRAM_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.PROGRAM_ID+"/"+JsonKey.COLLECTION_ID);
    validateParam(
            (String) programRequestDto.getRequest().get(JsonKey.BATCH_ID),
            ResponseCode.mandatoryParamsMissing,
            JsonKey.BATCH_ID);

    List<String> userIdList = (List<String>) programRequestDto.getRequest().get(JsonKey.USERID_LIST);
    validateParamCollection(
            userIdList,ResponseCode.mandatoryParamsMissing,
            JsonKey.USERID_LIST);
    if (userIdList != null&& userIdList.size()<30) {
      for (String userId : userIdList) {
        validateParam(
                userId,ResponseCode.invalidParameterValue,
                JsonKey.USER_ID);
      }
    }
  }
}
