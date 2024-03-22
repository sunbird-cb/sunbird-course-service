package org.sunbird.enrolments

import java.sql.Timestamp
import java.text.{MessageFormat, SimpleDateFormat}
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime, LocalTime, Month, ZoneId}
import java.util
import java.util.{Collections, Comparator, Date, UUID}
import akka.actor.ActorRef
import com.fasterxml.jackson.databind.ObjectMapper
import org.sunbird.common.models.util.JsonKey
import org.sunbird.learner.util.Util

import scala.collection.JavaConverters._
import javax.inject.{Inject, Named}
import org.apache.commons.collections4.{CollectionUtils, MapUtils}
import org.apache.commons.lang3.StringUtils
import org.sunbird.common.exception.ProjectCommonException
import org.sunbird.common.models.response.Response
import org.sunbird.common.models.util.ProjectUtil.{EnrolmentType, getConfigValue}
import org.sunbird.common.models.util._
import org.sunbird.common.request.{Request, RequestContext}
import org.sunbird.common.responsecode.ResponseCode
import org.sunbird.learner.actors.coursebatch.dao.impl.{BatchUserDaoImpl, CourseBatchDaoImpl, UserCoursesDaoImpl}
import org.sunbird.learner.actors.coursebatch.dao.{BatchUserDao, CourseBatchDao, UserCoursesDao}
import org.sunbird.learner.actors.group.dao.impl.GroupDaoImpl
import org.sunbird.learner.util.{ContentCacheHandler, ContentSearchUtil, ContentUtil, CourseBatchSchedulerUtil, JsonUtil, Util}
import org.sunbird.models.course.batch.CourseBatch
import org.sunbird.models.user.courses.UserCourses
import org.sunbird.cache.util.RedisCacheUtil
import org.sunbird.common.CassandraUtil
import org.sunbird.common.models.util.ProjectUtil
import org.sunbird.helper.ServiceFactory
import org.sunbird.kafka.client.{InstructionEventGenerator, KafkaClient}
import org.sunbird.learner.actors.course.dao.impl.ContentHierarchyDaoImpl
import org.sunbird.models.batch.user.BatchUser
import org.sunbird.telemetry.util.TelemetryUtil

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import scala.util.Try

class CourseEnrolmentActor @Inject()(@Named("course-batch-notification-actor") courseBatchNotificationActorRef: ActorRef
                                    )(implicit val  cacheUtil: RedisCacheUtil ) extends BaseEnrolmentActor {

    /*
    The below variables are kept as var on testcase purpose.
    TODO: once all are moved to scala, this can be made as parameterised constructor
     */
    var courseBatchDao: CourseBatchDao = new CourseBatchDaoImpl()
    var userCoursesDao: UserCoursesDao = new UserCoursesDaoImpl()
    var batchUserDao  : BatchUserDao   = new BatchUserDaoImpl()
    var groupDao: GroupDaoImpl = new GroupDaoImpl()
    var contentHierarchyDao: ContentHierarchyDaoImpl = new ContentHierarchyDaoImpl()
    var isRetiredCoursesIncludedInEnrolList = false
    val isCacheEnabled = if (StringUtils.isNotBlank(ProjectUtil.getConfigValue("user_enrolments_response_cache_enable")))
        (ProjectUtil.getConfigValue("user_enrolments_response_cache_enable")).toBoolean else true
    val ttl: Int = if (StringUtils.isNotBlank(ProjectUtil.getConfigValue("user_enrolments_response_cache_ttl")))
        (ProjectUtil.getConfigValue("user_enrolments_response_cache_ttl")).toInt else 60
    val redisCollectionIndex = if (StringUtils.isNotBlank(ProjectUtil.getConfigValue("redis_collection_index")))
        (ProjectUtil.getConfigValue("redis_collection_index")).toInt else 10
    private val DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd")
    private val pageDbInfo = Util.dbInfoMap.get(JsonKey.USER_KARMA_POINTS_DB)
    private val cassandraOperation = ServiceFactory.getInstance
    val jsonFields = Set[String]("lrcProgressDetails")
    private val mapper = new ObjectMapper
    override def preStart { println("Starting CourseEnrolmentActor") }

    override def postStop {
        cacheUtil.closePool()
        println("CourseEnrolmentActor stopped successfully")
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
        println(s"Restarting CourseEnrolmentActor: $message")
        reason.printStackTrace()
        super.preRestart(reason, message)
    }

    override def onReceive(request: Request): Unit = {
        Util.initializeContext(request, TelemetryEnvKey.BATCH, this.getClass.getName)

        request.getOperation match {
            case "enrol" => enroll(request)
            case "unenrol" => unEnroll(request)
            case "listEnrol" => list(request)
            case "enrolProgram" => enrollProgram(request)
            case "bulkEnrolProgram" => bulkEnrolProgram(request)
            case _ => ProjectCommonException.throwClientErrorException(ResponseCode.invalidRequestData,
                ResponseCode.invalidRequestData.getErrorMessage)
        }
    }

    def enroll(request: Request): Unit = {
        val courseId: String = request.get(JsonKey.COURSE_ID).asInstanceOf[String]
        val userId: String = request.get(JsonKey.USER_ID).asInstanceOf[String]
        val batchId: String = request.get(JsonKey.BATCH_ID).asInstanceOf[String]
        logger.info(request.asInstanceOf[Request].getRequestContext, "CourseEnrolmentActor Request for enroll recieved, UserId : " + userId + ", courseId : " + courseId +", batchId : "+batchId)
        val batchData: CourseBatch = courseBatchDao.readById( courseId, batchId, request.getRequestContext)
        val enrolmentData: UserCourses = userCoursesDao.read(request.getRequestContext, userId, courseId, batchId)
        val batchUserData: BatchUser = batchUserDao.read(request.getRequestContext, batchId, userId)
        validateEnrolment(batchData, enrolmentData, true)
        val dataBatch: util.Map[String, AnyRef] = createBatchUserMapping(batchId, userId,batchUserData)
        val data: java.util.Map[String, AnyRef] = createUserEnrolmentMap(userId, courseId, batchId, enrolmentData, request.getContext.getOrDefault(JsonKey.REQUEST_ID, "").asInstanceOf[String])
        val hasAccess = ContentUtil.getContentRead(courseId, request.getContext.getOrDefault(JsonKey.HEADER, new util.HashMap[String, String]).asInstanceOf[util.Map[String, String]])
        if (hasAccess) {
            upsertEnrollment(userId, courseId, batchId, data, dataBatch, (null == enrolmentData), request.getRequestContext)
            logger.info(request.getRequestContext, "CourseEnrolmentActor :: enroll :: Deleting redis for key " + getCacheKey(userId))
            cacheUtil.delete(getCacheKey(userId))
            sender().tell(successResponse(), self)
            generateTelemetryAudit(userId, courseId, batchId, data, "enrol", JsonKey.CREATE, request.getContext)
            notifyUser(userId, batchData, JsonKey.ADD)
            val dataMap = new java.util.HashMap[String, AnyRef]
            val requestMap = new java.util.HashMap[String, AnyRef]
            requestMap.put(JsonKey.COURSE_ID,courseId)
            requestMap.put(JsonKey.USER_ID,userId)
            requestMap.put(JsonKey.BATCH_ID,batchId)
            dataMap.put("edata",requestMap)
            val topic = ProjectUtil.getConfigValue("kafka_user_enrolment_event_topic")
            InstructionEventGenerator.createCourseEnrolmentEvent("", topic, dataMap)
        } else {
            ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, courseId)
        }
    }

    def unEnroll(request:Request): Unit = {
        val courseId: String = request.get(JsonKey.COURSE_ID).asInstanceOf[String]
        val userId: String = request.get(JsonKey.USER_ID).asInstanceOf[String]
        val batchId: String = request.get(JsonKey.BATCH_ID).asInstanceOf[String]
        logger.info(request.asInstanceOf[Request].getRequestContext, "CourseEnrolmentActor Request for un-enroll recieved, UserId : " + userId + ", courseId : " + courseId +", batchId : "+batchId)
        val batchData: CourseBatch = courseBatchDao.readById(courseId, batchId, request.getRequestContext)
        val enrolmentData: UserCourses = userCoursesDao.read(request.getRequestContext, userId, courseId, batchId)
        val batchUserData: BatchUser = batchUserDao.read(request.getRequestContext, batchId, userId)
        val dataBatch: util.Map[String, AnyRef] = createBatchUserMapping(batchId, userId,batchUserData)
        getUpdatedStatus(enrolmentData)
        validateEnrolment(batchData, enrolmentData, false)
        val data: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]() {{ put(JsonKey.ACTIVE, ProjectUtil.ActiveStatus.INACTIVE.getValue.asInstanceOf[AnyRef]) }}
        val hasAccess = ContentUtil.getContentRead(courseId, request.getContext.getOrDefault(JsonKey.HEADER, new util.HashMap[String, String]).asInstanceOf[util.Map[String, String]])
        if (hasAccess) {
            upsertEnrollment(userId,courseId, batchId, data, dataBatch, false, request.getRequestContext)
            logger.info(request.getRequestContext, "CourseEnrolmentActor :: unEnroll :: Deleting redis for key " + getCacheKey(userId))
            cacheUtil.delete(getCacheKey(userId))
            sender().tell(successResponse(), self)
            generateTelemetryAudit(userId, courseId, batchId, data, "unenrol", JsonKey.UPDATE, request.getContext)
            notifyUser(userId, batchData, JsonKey.REMOVE)
        } else {
            ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, courseId)
        }
    }

    def list(request: Request): Unit = {
        val userId = request.get(JsonKey.USER_ID).asInstanceOf[String]
        val urlQueryString = request.getContext.get(JsonKey.URL_QUERY_STRING).asInstanceOf[String]
        val paramMap = urlQueryString
          .split("&")
          .map(_.split("="))
          .collect { case Array(key, value) => key -> value }
          .toMap
        isRetiredCoursesIncludedInEnrolList = paramMap.getOrElse(JsonKey.RETIRED_COURE_ENABLED, JsonKey.FALSE).toBoolean
        val courseIdList = request.get(JsonKey.COURSE_IDS).asInstanceOf[java.util.List[String]]
        logger.info(request.getRequestContext,"CourseEnrolmentActor :: list :: UserId = " + userId)
        try{
            val response = if (isCacheEnabled && request.getContext.get("cache").asInstanceOf[Boolean])
                getCachedEnrolmentList(userId, () => getEnrolmentList(request, userId, courseIdList)) else getEnrolmentList(request, userId, courseIdList)
            sender().tell(response, self)
        }catch {
            case e: Exception =>
                logger.error(request.getRequestContext, "Exception in enrolment list : user ::" + userId + "| Exception is:"+e.getMessage, e)
                throw e
        }

    }

    def getActiveEnrollments(userId: String, courseIdList: java.util.List[String], requestContext: RequestContext): java.util.List[java.util.Map[String, AnyRef]] = {
        val enrolments: java.util.List[java.util.Map[String, AnyRef]] = userCoursesDao.listEnrolments(requestContext, userId, courseIdList);
        if (CollectionUtils.isNotEmpty(enrolments)) {
            if (isRetiredCoursesIncludedInEnrolList) {
                enrolments.toList.asJava
            } else {
                enrolments.filter(e => e.getOrDefault(JsonKey.ACTIVE, false.asInstanceOf[AnyRef]).asInstanceOf[Boolean]).toList.asJava
            }
        } else
            new util.ArrayList[java.util.Map[String, AnyRef]]()
    }

    def addCourseDetails(activeEnrolments: java.util.List[java.util.Map[String, AnyRef]], courseIds: java.util.List[String] , request:Request, flag:Boolean): java.util.List[java.util.Map[String, AnyRef]] = {
        val requestBody: String =  prepareSearchRequest(courseIds, request, flag)
        val searchResult:java.util.Map[String, AnyRef] = ContentSearchUtil.searchContentSync(request.getRequestContext, request.getContext.getOrDefault(JsonKey.URL_QUERY_STRING,"").asInstanceOf[String], requestBody, request.getContext.getOrDefault(JsonKey.HEADER, new util.HashMap[String, String]).asInstanceOf[util.Map[String, String]])
        val coursesList: java.util.List[java.util.Map[String, AnyRef]] = searchResult.getOrDefault(JsonKey.CONTENTS, new java.util.ArrayList[java.util.Map[String, AnyRef]]()).asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
        val coursesMap = {
            if(CollectionUtils.isNotEmpty(coursesList)) {
                coursesList.map(ev => ev.get(JsonKey.IDENTIFIER).asInstanceOf[String] -> ev).toMap
            } else Map()
        }
        activeEnrolments.filter(enrolment => coursesMap.containsKey(enrolment.get(JsonKey.COURSE_ID))).map(enrolment => {
            val courseContent = coursesMap.get(enrolment.get(JsonKey.COURSE_ID))
            enrolment.put(JsonKey.COURSE_NAME, courseContent.get(JsonKey.NAME))
            enrolment.put(JsonKey.DESCRIPTION, courseContent.get(JsonKey.DESCRIPTION))
            enrolment.put(JsonKey.LEAF_NODE_COUNT, courseContent.get(JsonKey.LEAF_NODE_COUNT))
            enrolment.put(JsonKey.COURSE_LOGO_URL, courseContent.get(JsonKey.APP_ICON))
            enrolment.put(JsonKey.CONTENT_ID, enrolment.get(JsonKey.COURSE_ID))
            enrolment.put(JsonKey.COLLECTION_ID, enrolment.get(JsonKey.COURSE_ID))
            enrolment.put(JsonKey.CONTENT, courseContent)
            enrolment
        }).toList.asJava
    }

    def prepareSearchRequest(courseIds: java.util.List[String], request: Request, flag:Boolean): String = {
        val status: util.List[String] = new util.ArrayList[String]()
        if (isRetiredCoursesIncludedInEnrolList) {
            status.add("Live")
            status.add("Retired")
        } else {
            status.add("Live")
        }
        val filters: java.util.Map[String, AnyRef] = new java.util.HashMap[String, AnyRef]() {{
            put(JsonKey.IDENTIFIER, courseIds)
            put(JsonKey.STATUS, status)
            put(JsonKey.MIME_TYPE, JsonKey.COLLECTION_MIME_TYPE)
            put(JsonKey.TRACKABLE_ENABLED, JsonKey.YES)
            putAll(request.getRequest.getOrDefault(JsonKey.FILTERS, new java.util.HashMap[String, AnyRef]).asInstanceOf[java.util.Map[String, AnyRef]])
        }}
        val searchRequest:java.util.Map[String, java.util.Map[String, AnyRef]] = new java.util.HashMap[String, java.util.Map[String, AnyRef]]() {{
            put(JsonKey.REQUEST, new java.util.HashMap[String, AnyRef](){{
                put(JsonKey.FILTERS, filters)
                put(JsonKey.SECURE_SETTINGS, flag.asInstanceOf[AnyRef])
                put(JsonKey.LIMIT, courseIds.size().asInstanceOf[AnyRef])
            }})
        }}
        new ObjectMapper().writeValueAsString(searchRequest)
    }

    def addBatchDetails(enrolmentList: util.List[util.Map[String, AnyRef]], request: Request): util.List[util.Map[String, AnyRef]] = {
        val batchIds:java.util.List[String] = enrolmentList.map(e => e.getOrDefault(JsonKey.BATCH_ID, "").asInstanceOf[String]).distinct.filter(id => StringUtils.isNotBlank(id)).toList.asJava
        val batchDetails = new java.util.ArrayList[java.util.Map[String, AnyRef]]();
        val searchIdentifierMaxSize = Integer.parseInt(ProjectUtil.getConfigValue(JsonKey.SEARCH_IDENTIFIER_MAX_SIZE));
        if (batchIds.size() > searchIdentifierMaxSize) {
            for (i <- 0 to batchIds.size() by searchIdentifierMaxSize) {
                val batchIdsSubList: java.util.List[String] = batchIds.subList(i, Math.min(batchIds.size(), i + searchIdentifierMaxSize));
                batchDetails.addAll(searchBatchDetails(batchIdsSubList, request))
            }
        } else {
           batchDetails.addAll(searchBatchDetails(batchIds, request))
        }
        if(CollectionUtils.isNotEmpty(batchDetails)){
            val batchMap = batchDetails.map(b => b.get(JsonKey.BATCH_ID).asInstanceOf[String] -> b).toMap
            enrolmentList.map(enrolment => {
                enrolment.put(JsonKey.BATCH, batchMap.getOrElse(enrolment.get(JsonKey.BATCH_ID).asInstanceOf[String], new java.util.HashMap[String, AnyRef]()))
                //To Do : A temporary change to support updation of completed course remove in next release
                //                if (enrolment.get("progress").asInstanceOf[Integer] < enrolment.get("leafNodesCount").asInstanceOf[Integer]) {
                //                    enrolment.put("status", 1.asInstanceOf[Integer])
                //                    enrolment.put("completedOn", null)
                //                }
                enrolment
            }).toList.asJava
        } else
            enrolmentList
    }

    def addBatchDetails_v2(enrolmentList: util.List[util.Map[String, AnyRef]], request: Request): util.List[util.Map[String, AnyRef]] = {
        val blendedEnrolments = enrolmentList
          .filter(enrolment =>
              enrolment.get("primaryCategory") == "Blended Program" &&
                StringUtils.isNotBlank(enrolment.getOrDefault(JsonKey.BATCH_ID, "").asInstanceOf[String])
          )
        if (blendedEnrolments.isEmpty) {
            return enrolmentList
        }
        val batchIds: java.util.List[String] = blendedEnrolments
          .map(e => e.getOrDefault(JsonKey.BATCH_ID, "").asInstanceOf[String])
          .distinct
          .toList
          .asJava
        val batchDetails = searchBatchDetails(batchIds, request)
        if (CollectionUtils.isNotEmpty(batchDetails)) {
            val batchMap = batchDetails.map(b => b.get(JsonKey.BATCH_ID).asInstanceOf[String] -> b).toMap
            blendedEnrolments.map(enrolment => {
                enrolment.put(JsonKey.BATCH, batchMap.getOrElse(enrolment.get(JsonKey.BATCH_ID).asInstanceOf[String], new java.util.HashMap[String, AnyRef]()))
                enrolment
            }).toList.asJava
        } else {
            enrolmentList
        }
    }

    def searchBatchDetails(batchIds: java.util.List[String], request: Request): java.util.List[java.util.Map[String, AnyRef]] = {
        val requestedFields: java.util.List[String] = if(null != request.getContext.get(JsonKey.BATCH_DETAILS).asInstanceOf[Array[String]]) request.getContext.get(JsonKey.BATCH_DETAILS).asInstanceOf[Array[String]](0).split(",").toList.asJava else new java.util.ArrayList[String]()
        if(CollectionUtils.isNotEmpty(requestedFields)) {
          val fields = new java.util.ArrayList[String]()
            fields.addAll(requestedFields)
            fields.add(JsonKey.BATCH_ID)
            fields.add(JsonKey.IDENTIFIER)
          getBatches(request.getRequestContext ,new java.util.ArrayList[String](batchIds), fields)
        } else {
            new java.util.ArrayList[util.Map[String, AnyRef]]()
        }
    }


    def validateEnrolment(batchData: CourseBatch, enrolmentData: UserCourses, isEnrol: Boolean): Unit = {
        if(null == batchData) ProjectCommonException.throwClientErrorException(ResponseCode.invalidCourseBatchId, ResponseCode.invalidCourseBatchId.getErrorMessage)

        if(!(EnrolmentType.inviteOnly.getVal.equalsIgnoreCase(batchData.getEnrollmentType) ||
          EnrolmentType.open.getVal.equalsIgnoreCase(batchData.getEnrollmentType)))
            ProjectCommonException.throwClientErrorException(ResponseCode.enrollmentTypeValidation, ResponseCode.enrollmentTypeValidation.getErrorMessage)

        if((2 == batchData.getStatus) || (null != batchData.getEndDate && LocalDateTime.now().isAfter(LocalDate.parse(DATE_FORMAT.format(batchData.getEndDate), DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(LocalTime.MAX))))
            ProjectCommonException.throwClientErrorException(ResponseCode.courseBatchAlreadyCompleted, ResponseCode.courseBatchAlreadyCompleted.getErrorMessage)

        if(isEnrol && null != batchData.getEnrollmentEndDate && LocalDateTime.now().isAfter(LocalDate.parse(DATE_FORMAT.format(batchData.getEnrollmentEndDate), DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(LocalTime.MAX)))
            ProjectCommonException.throwClientErrorException(ResponseCode.courseBatchEnrollmentDateEnded, ResponseCode.courseBatchEnrollmentDateEnded.getErrorMessage)

        if(isEnrol && null != enrolmentData && enrolmentData.isActive) ProjectCommonException.throwClientErrorException(ResponseCode.userAlreadyEnrolledCourse, ResponseCode.userAlreadyEnrolledCourse.getErrorMessage)
        if(!isEnrol && (null == enrolmentData || !enrolmentData.isActive)) ProjectCommonException.throwClientErrorException(ResponseCode.userNotEnrolledCourse, ResponseCode.userNotEnrolledCourse.getErrorMessage)
        if(!isEnrol && ProjectUtil.ProgressStatus.COMPLETED.getValue == enrolmentData.getStatus) ProjectCommonException.throwClientErrorException(ResponseCode.courseBatchAlreadyCompleted, ResponseCode.courseBatchAlreadyCompleted.getErrorMessage)
    }

    def upsertEnrollment(userId: String, courseId: String, batchId: String, data: java.util.Map[String, AnyRef], dataBatch:java.util.Map[String, AnyRef] ,isNew: Boolean, requestContext: RequestContext): Unit = {
        val dataMap = CassandraUtil.changeCassandraColumnMapping(data)
        val dataBatchMap = CassandraUtil.changeCassandraColumnMapping(dataBatch)

        // code for  find root cause of null value in prod(16-02-2023)
        try {
            val activeStatus = dataMap.get(JsonKey.ACTIVE);
            logger.info(requestContext, "upsertEnrollment :: IsNew :: " + isNew + " ActiveStatus :: " + activeStatus + " DataMap is :: " + dataMap+ " DataBatchMap:: "+ dataBatchMap)
            if (activeStatus == null) {
                throw new Exception("Active Value is null in upsertEnrollment");
            }
        } catch {
            case e: Exception =>
                logger.error(requestContext, "Exception in upsertEnrollment list : user ::" + userId + "| Exception is:" + e.getMessage, e)
                throw e;
        }
        // END
        if(isNew) {
            userCoursesDao.insertV2(requestContext, dataMap)
            batchUserDao.insertBatchLookupRecord(requestContext,dataBatchMap)
        } else {
            userCoursesDao.updateV2(requestContext, userId, courseId, batchId, dataMap)
            batchUserDao.updateBatchLookupRecord(requestContext, batchId, userId, dataBatchMap, dataMap)
        }
    }

    def createUserEnrolmentMap(userId: String, courseId: String, batchId: String, enrolmentData: UserCourses, requestedBy: String): java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]() {{
            put(JsonKey.USER_ID, userId)
            put(JsonKey.COURSE_ID, courseId)
            put(JsonKey.BATCH_ID, batchId)
            put(JsonKey.ACTIVE, ProjectUtil.ActiveStatus.ACTIVE.getValue.asInstanceOf[AnyRef])
            if (null == enrolmentData) {
                put(JsonKey.ADDED_BY, requestedBy)
                put(JsonKey.COURSE_ENROLL_DATE, ProjectUtil.getTimeStamp)
                put(JsonKey.STATUS, ProjectUtil.ProgressStatus.NOT_STARTED.getValue.asInstanceOf[AnyRef])
                put(JsonKey.DATE_TIME, new Timestamp(new Date().getTime))
                put(JsonKey.COURSE_PROGRESS, 0.asInstanceOf[AnyRef])
            }
        }}

    def createBatchUserMapping(batchId: String, userId: String,batchUserData :BatchUser): java.util.Map[String, AnyRef] =
        new java.util.HashMap[String, AnyRef]() {
            put(JsonKey.BATCH_ID, batchId)
            put(JsonKey.USER_ID, userId)
            put(JsonKey.ACTIVE, ProjectUtil.ActiveStatus.ACTIVE.getValue.asInstanceOf[AnyRef])
            if(batchUserData == null) {
                put(JsonKey.COURSE_ENROLL_DATE, ProjectUtil.getTimeStamp)
            } else {
                put(JsonKey.COURSE_ENROLL_DATE, batchUserData.getEnrolledDate)
            }
        }

    def notifyUser(userId: String, batchData: CourseBatch, operationType: String): Unit = {
        val isNotifyUser = java.lang.Boolean.parseBoolean(PropertiesCache.getInstance().getProperty(JsonKey.SUNBIRD_COURSE_BATCH_NOTIFICATIONS_ENABLED))
        if(isNotifyUser){
            val request = new Request()
            request.setOperation(ActorOperations.COURSE_BATCH_NOTIFICATION.getValue)
            request.put(JsonKey.USER_ID, userId)
            request.put(JsonKey.COURSE_BATCH, batchData)
            request.put(JsonKey.OPERATION_TYPE, operationType)
            courseBatchNotificationActorRef.tell(request, getSelf())
        }
    }

    def generateTelemetryAudit(userId: String, courseId: String, batchId: String, data: java.util.Map[String, AnyRef], correlation: String, state: String, context: java.util.Map[String, AnyRef]): Unit = {
        val contextMap = new java.util.HashMap[String, AnyRef]()
        contextMap.putAll(context)
        contextMap.put(JsonKey.ACTOR_ID, userId)
        contextMap.put(JsonKey.ACTOR_TYPE, "User")
        val targetedObject = TelemetryUtil.generateTargetObject(userId, JsonKey.USER, state, null)
        targetedObject.put(JsonKey.ROLLUP, new java.util.HashMap[String, AnyRef](){{put("l1", courseId)}})
        val correlationObject = new java.util.ArrayList[java.util.Map[String, AnyRef]]()
        TelemetryUtil.generateCorrelatedObject(courseId, JsonKey.COURSE, correlation, correlationObject)
        TelemetryUtil.generateCorrelatedObject(batchId, TelemetryEnvKey.BATCH, "user.batch", correlationObject)
        val request: java.util.Map[String, AnyRef] = Map[String, AnyRef](JsonKey.USER_ID -> userId, JsonKey.COURSE_ID -> courseId, JsonKey.BATCH_ID -> batchId, JsonKey.COURSE_ENROLL_DATE -> data.get(JsonKey.COURSE_ENROLL_DATE), JsonKey.ACTIVE -> data.get(JsonKey.ACTIVE)).asJava
        TelemetryUtil.telemetryProcessingCall(request, targetedObject, correlationObject, contextMap, "enrol")
    }

    def updateProgressData(enrolments: java.util.List[java.util.Map[String, AnyRef]], userId: String, courseIds: java.util.List[String], requestContext: RequestContext): util.List[java.util.Map[String, AnyRef]] = {
        enrolments.map(enrolment => {
            val leafNodesCount: Int = enrolment.getOrDefault("leafNodesCount", 0.asInstanceOf[AnyRef]).asInstanceOf[Int]
            val progress: Int = enrolment.getOrDefault("progress", 0.asInstanceOf[AnyRef]).asInstanceOf[Int]
            enrolment.put("status", getCompletionStatus(progress, leafNodesCount).asInstanceOf[AnyRef])
            enrolment.put("completionPercentage", getCompletionPerc(progress, leafNodesCount).asInstanceOf[AnyRef])
            jsonFields.foreach(field =>
                if (enrolment.containsKey(field) && null != enrolment.get(field)) {
                    enrolment.put(field, mapper.readTree(enrolment.get(field).asInstanceOf[String]))
                } else enrolment.put(field, new util.HashMap[String, AnyRef]())
            )
        })
        enrolments
    }

    def getCompletionStatus(completedCount: Int, leafNodesCount: Int): Int = completedCount match {
        case 0 => 0
        case it if 1 until leafNodesCount contains it => 1
        case `leafNodesCount` => 2
        case _ => 2
    }

    def getCompletionPerc(completedCount: Int, leafNodesCount: Int): Int = completedCount match {
        case 0 => 0
        case it if 1 until leafNodesCount contains it => (completedCount * 100) / leafNodesCount
        case `leafNodesCount` => 100
        case _ => 100
    }

    def getCacheKey(userId: String) = s"$userId:user-enrolments"

    def getCachedEnrolmentList(userId: String, handleEmptyCache: () => Response): Response = {
        val key = getCacheKey(userId)
        val responseString = cacheUtil.get(key)
        if (StringUtils.isNotBlank(responseString)) {
            JsonUtil.deserialize(responseString, classOf[Response])
        } else {
            val response = handleEmptyCache()
            val responseString = JsonUtil.serialize(response)
            cacheUtil.set(key, responseString, ttl)
            response
        }
    }

    def getEnrolmentList(request: Request, userId: String, courseIdList: java.util.List[String]): Response = {
        logger.info(request.getRequestContext,"CourseEnrolmentActor :: getCachedEnrolmentList :: fetching data from cassandra with userId " + userId)
        //ContentUtil.getAllContent(PropertiesCache.getInstance.getProperty(JsonKey.PAGE_SIZE_CONTENT_FETCH).toInt)
        val activeEnrolments: java.util.List[java.util.Map[String, AnyRef]] = getActiveEnrollments( userId, courseIdList, request.getRequestContext)
        val enrolments: java.util.List[java.util.Map[String, AnyRef]] = {
            if (CollectionUtils.isNotEmpty(activeEnrolments)) {
              val allCourseIds: java.util.List[String] = activeEnrolments.map(e => e.getOrDefault(JsonKey.COURSE_ID, "").asInstanceOf[String]).distinct.filter(id => StringUtils.isNotBlank(id)).toList.asJava
                val courseIds = new java.util.ArrayList[String]()
                val secureCourseIds = new java.util.ArrayList[String]()
                for (courseId <- allCourseIds.asScala) {
                    if (courseId.endsWith("_rc")) {
                        secureCourseIds.add(courseId)
                    } else {
                        courseIds.add(courseId)
                    }
                }
                val allEnrolledCourses = new java.util.ArrayList[java.util.Map[String, AnyRef]]
                val version = request.getContext.get("version")
                if ("v2".equals(version)) {
                    val enrolmentList: java.util.List[java.util.Map[String, AnyRef]] = addCourseDetails_v2(activeEnrolments)
                    if (enrolmentList != null) {
                        allEnrolledCourses.addAll(enrolmentList)
                    }
                }
                else {
                    if (CollectionUtils.isNotEmpty(courseIds)) {
                        val enrolmentList: java.util.List[java.util.Map[String, AnyRef]] = addCourseDetails(activeEnrolments, courseIds, request, false)
                        if (enrolmentList != null) {
                            allEnrolledCourses.addAll(enrolmentList)
                        }
                    }
                    if (CollectionUtils.isNotEmpty(secureCourseIds)) {
                        val secureCourseEnrolmentList: java.util.List[java.util.Map[String, AnyRef]] = addCourseDetails(activeEnrolments, secureCourseIds, request, true)
                        if (secureCourseEnrolmentList != null) {
                            allEnrolledCourses.addAll(secureCourseEnrolmentList)
                        }
                    }
                }
                val updatedEnrolmentList = updateProgressData(allEnrolledCourses, userId, allCourseIds, request.getRequestContext)
                //if ("v2".equals(version))
                //    addBatchDetails_v2(updatedEnrolmentList, request)
                //else
                addBatchDetails(updatedEnrolmentList, request)

            } else new java.util.ArrayList[java.util.Map[String, AnyRef]]()
        }
        val resp: Response = new Response()
        val sortedEnrolment = enrolments.filter(ae => ae.get("lastContentAccessTime")!=null).toList.sortBy(_.get("lastContentAccessTime").asInstanceOf[Date])(Ordering[Date].reverse).toList
        val finalEnrolments = sortedEnrolment ++ enrolments.asScala.filter(e => e.get("lastContentAccessTime")==null).toList
        val userCourseEnrolmentInfo = getUserEnrolmentCourseInfo(finalEnrolments, request, userId);
        resp.put(JsonKey.USER_COURSE_ENROLMENT_INFO, userCourseEnrolmentInfo)
        resp.put(JsonKey.COURSES, finalEnrolments.asJava)
        resp
    }
    // TODO: to be removed once all are in scala.
    def setDao(courseDao: CourseBatchDao, userDao: UserCoursesDao, groupDao: GroupDaoImpl) = {
        courseBatchDao = courseDao
        userCoursesDao = userDao
        this.groupDao = groupDao
        this
    }


    def getUpdatedStatus(enrolmentData: UserCourses) = {
        val query = "{\"request\": {\"filters\":{\"identifier\": \"" + enrolmentData.getCourseId +"\", \"status\": \"Live\"},\"fields\": [\"leafNodesCount\"],\"limit\": 1}}"
        val result = ContentUtil.searchContent(query, CourseBatchSchedulerUtil.headerMap)
        val contents = result.getOrDefault(JsonKey.CONTENTS, new java.util.ArrayList[java.util.Map[String, AnyRef]]).asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
        val leafNodesCount = {if(CollectionUtils.isNotEmpty(contents)){
            contents.get(0).asInstanceOf[java.util.Map[String, AnyRef]].getOrDefault(JsonKey.LEAF_NODE_COUNT, 0.asInstanceOf[AnyRef]).asInstanceOf[Int]
        } else 0}
        enrolmentData.setStatus(getCompletionStatus(enrolmentData.getProgress, leafNodesCount))
    }

    def addCourseDetails_v2(activeEnrolments: java.util.List[java.util.Map[String, AnyRef]]): java.util.List[java.util.Map[String, AnyRef]] = {
        val coursesMap = ContentCacheHandler.getContentMap.asInstanceOf[java.util.Map[String, java.util.Map[String, AnyRef]]]
        activeEnrolments.filter(enrolment => coursesMap.containsKey(enrolment.get(JsonKey.COURSE_ID))).map(enrolment => {
            var courseContent = coursesMap.get(enrolment.get(JsonKey.COURSE_ID))
            if (courseContent == null || courseContent.size() < 1)
                courseContent = ContentCacheHandler.getContent(enrolment.get(JsonKey.COURSE_ID).asInstanceOf[String])
            enrolment.put(JsonKey.COURSE_NAME, courseContent.get(JsonKey.NAME))
            enrolment.put(JsonKey.DESCRIPTION, courseContent.get(JsonKey.DESCRIPTION))
            enrolment.put(JsonKey.LEAF_NODE_COUNT, courseContent.get(JsonKey.LEAF_NODE_COUNT))
            enrolment.put(JsonKey.COURSE_LOGO_URL, courseContent.get(JsonKey.APP_ICON))
            enrolment.put(JsonKey.CONTENT_ID, enrolment.get(JsonKey.COURSE_ID))
            enrolment.put(JsonKey.COLLECTION_ID, enrolment.get(JsonKey.COURSE_ID))
            enrolment.put(JsonKey.CONTENT, courseContent)
            enrolment
        }).toList.asJava
    }
    def enrollProgram(request: Request): Unit = {
        val programId: String = request.get(JsonKey.PROGRAM_ID).asInstanceOf[String]
        val isAdminAPI: Boolean = request.get(JsonKey.IS_ADMIN_API).asInstanceOf[Boolean]
        val fieldList = List(JsonKey.PRIMARYCATEGORY, JsonKey.IDENTIFIER, JsonKey.BATCHES)
        val contentData = getContentReadAPIData(programId, fieldList, request)
        if (isAdminAPI && (contentData.size() == 0 || !util.Arrays.asList(getConfigValue(JsonKey.ADMIN_PROGRAM_ENROLL_ALLOWED_PRIMARY_CATEGORY).split(","): _*).contains(contentData.get(JsonKey.PRIMARYCATEGORY).asInstanceOf[String])))
            ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, programId);
        if (!isAdminAPI && (contentData.size() == 0 || !util.Arrays.asList(getConfigValue(JsonKey.PROGRAM_ENROLL_ALLOWED_PRIMARY_CATEGORY).split(","): _*).contains(contentData.get(JsonKey.PRIMARYCATEGORY).asInstanceOf[String])))
            ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, programId);
        val userId: String = request.get(JsonKey.USER_ID).asInstanceOf[String]
        val batchId: String = request.get(JsonKey.BATCH_ID).asInstanceOf[String]
        val batchData: CourseBatch = courseBatchDao.readById(programId, batchId, request.getRequestContext)
        val verifyBatchType: Boolean = Option(request.getContext.get("verifyBatchType").asInstanceOf[Boolean]).getOrElse(false)
        if(verifyBatchType && !("open".equalsIgnoreCase(batchData.getEnrollmentType))) {
            ProjectCommonException.throwClientErrorException(ResponseCode.notOpenBatch);
        }
        var enrolmentData: UserCourses = null
        val enrolmentDataList: java.util.List[UserCourses] = userCoursesDao.readAll(request.getRequestContext, userId, programId)
        if (null != enrolmentDataList) {
            for (enrolment <- enrolmentDataList) {
                if (enrolment.isActive) {
                    ProjectCommonException.throwClientErrorException(ResponseCode.userAlreadyEnrolledCourse);
                }
                if (enrolment.getBatchId.equals(batchId)) {
                    enrolmentData = enrolment
                }
            }
        }
        val batchUserData: BatchUser = batchUserDao.read(request.getRequestContext, batchId, userId)
        validateEnrolment(batchData, enrolmentData, true)
        getCoursesForProgramAndEnrol(request, programId, userId, batchId)
        val dataBatch: util.Map[String, AnyRef] = createBatchUserMapping(batchId, userId, batchUserData)
        val data: java.util.Map[String, AnyRef] = createUserEnrolmentMap(userId, programId, batchId, enrolmentData, request.getContext.getOrDefault(JsonKey.REQUEST_ID, "").asInstanceOf[String])
        upsertEnrollment(userId, programId, batchId, data, dataBatch, (null == enrolmentData), request.getRequestContext)
        logger.info(request.getRequestContext, "ProgramEnrolmentActor :: enroll :: Deleting redis for key " + getCacheKey(userId))
        cacheUtil.delete(getCacheKey(userId))
        generatePreProcessorKafkaEvent(request,batchId, programId, userId)
        sender().tell(successResponse(), self)
        generateTelemetryAudit(userId, programId, batchId, data, "enrol", JsonKey.CREATE, request.getContext)
        notifyUser(userId, batchData, JsonKey.ADD)
    }

    def getContentReadAPIData(programId: String, fieldList: List[String], request: Request): util.Map[String, AnyRef] = {
        val responseString: String = cacheUtil.get(programId)
        val contentData: util.Map[String, AnyRef] = if (StringUtils.isNotBlank(responseString)) {
            JsonUtil.deserialize(responseString, new util.HashMap[String, AnyRef]().getClass)
        } else {
            ContentUtil.getContentReadV3(programId, fieldList, request.getContext.getOrDefault(JsonKey.HEADER, new util.HashMap[String, String]).asInstanceOf[util.Map[String, String]])
        }
        contentData
    }

    /**
     *
     * @param batchId   -BatchId of the Program
     * @param programId - ProgramId of the Program
     * @param userId    - UserId of the Program
     */
    def generatePreProcessorKafkaEvent(request: Request, batchId: String, programId: String, userId: String): Unit = {
        //for generating the kafka event for program generate certificate
        logger.info(request.getRequestContext, "Inside the generatePreProcessorKafkaEvent")
        val ets = System.currentTimeMillis
        val mid = s"""LP.${ets}.${UUID.randomUUID}"""
        val event = s"""{"eid": "BE_JOB_REQUEST","ets": ${ets},"mid": "${mid}","actor": {"id": "Program Certificate Pre Processor Generator","type": "System"},"context": {"pdata": {"ver": "1.0","id": "org.sunbird.platform"}},"object": {"id": "${batchId}_${programId}","type": "ProgramCertificatePreProcessorGeneration"},"edata": {"userId": "${userId}","action": "program-issue-certificate","iteration": 1, "trigger": "auto-issue","batchId": "${batchId}","parentCollections": ["${programId}"],"courseId": "${programId}"}}"""
        val topic = ProjectUtil.getConfigValue("kafka_cert_pre_processor_topic")
        if (StringUtils.isNotBlank(topic)) KafkaClient.send(event, topic)
        else throw new ProjectCommonException("BE_JOB_REQUEST_EXCEPTION", "Invalid topic id.", ResponseCode.CLIENT_ERROR.getResponseCode)
    }

    def getCoursesForProgramAndEnrol(request: Request, programId: String, userId: String, batchId: String) = {
        val redisKey = s"$programId:$programId:childrenCourses"
        val childrenNodes: List[String] = cacheUtil.getList(redisKey, redisCollectionIndex)
        val courseBatchMap: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]()
        if (!childrenNodes.isEmpty) {
            for (childNode <- childrenNodes) {
                val contentData = getContentReadAPIData(childNode, List(JsonKey.PRIMARYCATEGORY), request)
                val primaryCategory: String = contentData.get(JsonKey.PRIMARYCATEGORY).asInstanceOf[String]
                if (util.Arrays.asList(getConfigValue(JsonKey.PROGRAM_ENROLL_RESTRICTED_CHILDREN_PRIMARY_CATEGORY).split(","): _*).contains(primaryCategory))
                    ProjectCommonException.throwClientErrorException(ResponseCode.contentTypeMismatch, childNode)
                else if (util.Arrays.asList(getConfigValue(JsonKey.PROGRAM_ENROLL_ALLOWED_CHILDREN_PRIMARY_CATEGORY).split(","): _*).contains(primaryCategory)) {
                    try {
                        val batchData: CourseBatch = courseBatchDao.readFirstAvailableBatch(childNode, request.getRequestContext)
                        courseBatchMap.put(childNode, batchData)
                    } catch {
                        case e: ProjectCommonException => ProjectCommonException.throwClientErrorException(ResponseCode.courseDoesNotHaveBatch);
                    }
                } else {
                    logger.info(request.getRequestContext, "Skipping the enrol for Primary Category" + primaryCategory)
                }
            }
        } else {
            val contentDataForProgram: java.util.List[java.util.Map[String, AnyRef]] = contentHierarchyDao.getContentChildren(request.getRequestContext, programId)
            for (childNode <- contentDataForProgram.asScala) {
                val courseId: String = childNode.get(JsonKey.IDENTIFIER).asInstanceOf[String]
                val primaryCategory: String = childNode.get(JsonKey.PRIMARYCATEGORY).asInstanceOf[String]
                if (util.Arrays.asList(getConfigValue(JsonKey.PROGRAM_ENROLL_RESTRICTED_CHILDREN_PRIMARY_CATEGORY).split(","): _*).contains(primaryCategory))
                    ProjectCommonException.throwClientErrorException(ResponseCode.contentTypeMismatch, courseId)
                else if (util.Arrays.asList(getConfigValue(JsonKey.PROGRAM_ENROLL_ALLOWED_CHILDREN_PRIMARY_CATEGORY).split(","): _*).contains(primaryCategory)) {
                    try {
                        val batchData: CourseBatch = courseBatchDao.readFirstAvailableBatch(courseId, request.getRequestContext)
                        courseBatchMap.put(courseId, batchData)
                    } catch {
                        case e: ProjectCommonException => ProjectCommonException.throwClientErrorException(ResponseCode.courseDoesNotHaveBatch);
                    }
                } else {
                    logger.info(request.getRequestContext, "Skipping the enrol for Primary Category" + primaryCategory)
                }
            }
        }
        for (courseId <- courseBatchMap.keySet()) {
            // Enroll in course with courseId, userId and batchId.
            enrollProgramCourses(request, courseId, courseBatchMap.get(courseId).asInstanceOf[CourseBatch])
        }
    }

    def enrollProgramCourses(request: Request,courseId: String,batchData:CourseBatch): Boolean = {
        try {
            val userId: String = request.get(JsonKey.USER_ID).asInstanceOf[String]
            val batchId: String = batchData.getBatchId.asInstanceOf[String]
            val enrolmentData: UserCourses = userCoursesDao.read(request.getRequestContext, userId, courseId, batchId)
            val batchUserData: BatchUser = batchUserDao.read(request.getRequestContext, batchId, userId)
            validateEnrolment(batchData, enrolmentData, true)
            val dataBatch: util.Map[String, AnyRef] = createBatchUserMapping(batchId, userId, batchUserData)
            val data: java.util.Map[String, AnyRef] = createUserEnrolmentMap(userId, courseId, batchId, enrolmentData, request.getContext.getOrDefault(JsonKey.REQUEST_ID, "").asInstanceOf[String])
            upsertEnrollment(userId, courseId, batchId, data, dataBatch, (null == enrolmentData), request.getRequestContext)
            logger.info(request.getRequestContext, "CourseEnrolmentActor :: enroll :: Deleting redis for key " + getCacheKey(userId))
            cacheUtil.delete(getCacheKey(userId))
            generateTelemetryAudit(userId, courseId, batchId, data, "enrol", JsonKey.CREATE, request.getContext)
            notifyUser(userId, batchData, JsonKey.ADD)
        } catch {
            case e: ProjectCommonException =>
                if (ResponseCode.userAlreadyEnrolledCourse.getErrorMessage.equals(e.getMessage))
                    return true
                if (ResponseCode.userAlreadyCompletedCourse.getErrorMessage.equals(e.getMessage))
                    return true
                if (ResponseCode.courseBatchEnrollmentDateEnded.getErrorMessage.equals(e.getMessage))
                    ProjectCommonException.throwClientErrorException(ResponseCode.courseBatchEnrollmentDateEnded, ResponseCode.courseBatchEnrollmentDateEnded.getErrorMessage)
                if (ResponseCode.userNotEnrolledCourse.getErrorMessage.equals(e.getMessage))
                    ProjectCommonException.throwClientErrorException(ResponseCode.userNotEnrolledCourse, ResponseCode.userNotEnrolledCourse.getErrorMessage)
            case e: Exception =>
                logger.error(request.getRequestContext, "Exception in upsertEnrollment list : user ::" + e.getMessage, e)
                ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, request.get(JsonKey.COURSE_ID).asInstanceOf[String]);
        }
        false;
    }

    def getUserEnrolmentCourseInfo(finalEnrolment: List[util.Map[String, AnyRef]], actorMessage: Request, userId: String) = {
        var certificateIssued: Int = 0
        var coursesInProgress: Int = 0
        var hoursSpentOnCompletedCourses: Int = 0
        var addInfo: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]()
        finalEnrolment.foreach { courseDetails =>
            val courseStatus = courseDetails.get(JsonKey.STATUS)
            if (courseStatus != 2) {
                coursesInProgress += 1
            } else {
                val courseContent: java.util.HashMap[String, AnyRef] = courseDetails.get(JsonKey.CONTENT).asInstanceOf[java.util.HashMap[String, AnyRef]]
                var hoursSpentOnCourses: Int = 0
                if (null != courseContent.get(JsonKey.DURATION)) {
                    hoursSpentOnCourses = courseContent.get(JsonKey.DURATION).asInstanceOf[String].toInt
                }
                hoursSpentOnCompletedCourses += hoursSpentOnCourses
                val certificatesIssue: java.util.ArrayList[util.Map[String, AnyRef]] = courseDetails.get(JsonKey.ISSUED_CERTIFICATES).asInstanceOf[java.util.ArrayList[util.Map[String, AnyRef]]]
                if (certificatesIssue.nonEmpty) {
                    certificateIssued += 1
                }
            }
        }
        val userKarmaPoints = cassandraOperation.getRecordsByProperty(
            actorMessage.getRequestContext,
            pageDbInfo.getKeySpace,
            pageDbInfo.getTableName,
            JsonKey.USER_ID,
            userId,
            util.Arrays.asList(JsonKey.USER_KARMA_TOTAL_POINTS, JsonKey.ADD_INFO)
        )
        //dbResponse is a list of maps to extract points for each record
        val dbResponse: java.util.List[util.Map[String, AnyRef]] = userKarmaPoints.get(JsonKey.RESPONSE).asInstanceOf[java.util.List[util.Map[String, AnyRef]]]
        val totalUserKarmaPoints: Int = dbResponse.asScala.collectFirst {
            case record: util.Map[String, AnyRef] if record.containsKey(JsonKey.USER_KARMA_TOTAL_POINTS) =>
                record.get(JsonKey.USER_KARMA_TOTAL_POINTS).asInstanceOf[Integer].toInt
        }.getOrElse(0)
        val addInfoString: String = if (dbResponse.isEmpty) {
            ""
        } else {
            Option(dbResponse.get(0)).flatMap(record => Option(record.get(JsonKey.ADD_INFO)).collect { case str: String => str }).getOrElse("")
        }
        if (addInfoString != null && addInfoString.nonEmpty) {
            val objectMapper = new ObjectMapper().registerModule(DefaultScalaModule)
            addInfo = objectMapper.readValue(addInfoString, classOf[util.Map[String, AnyRef]])
        }
        val enrolmentCourseDetails = new util.HashMap[String, AnyRef]()
        enrolmentCourseDetails.put(JsonKey.TIME_SPENT_ON_COMPLETED_COURSES, hoursSpentOnCompletedCourses.asInstanceOf[AnyRef])
        enrolmentCourseDetails.put(JsonKey.CERITFICATES_ISSUED, certificateIssued.asInstanceOf[AnyRef])
        enrolmentCourseDetails.put(JsonKey.COURSES_IN_PROGRESS, coursesInProgress.asInstanceOf[AnyRef])
        enrolmentCourseDetails.put(JsonKey.KARMA_POINTS, totalUserKarmaPoints.asInstanceOf[AnyRef])
        enrolmentCourseDetails.put(JsonKey.ADD_INFO, addInfo.asInstanceOf[AnyRef])
        enrolmentCourseDetails
    }

    def bulkEnrolProgram(request: Request): Unit = {
        val response: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]()
        val status: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]()
        val map: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]()
        val resp: Response = new Response()
        val programId: String = request.get(JsonKey.PROGRAM_ID).asInstanceOf[String]
        val isAdminAPI: Boolean = request.get(JsonKey.IS_ADMIN_API).asInstanceOf[Boolean]
        val fieldList = List(JsonKey.PRIMARYCATEGORY, JsonKey.IDENTIFIER, JsonKey.BATCHES)
        val contentData = getContentReadAPIData(programId, fieldList, request)

        if (isAdminAPI && (contentData.size() == 0 || !util.Arrays.asList(getConfigValue(JsonKey.ADMIN_PROGRAM_ENROLL_ALLOWED_PRIMARY_CATEGORY).split(","): _*).contains(contentData.get(JsonKey.PRIMARYCATEGORY).asInstanceOf[String])))
            ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, programId);

        if (!isAdminAPI && (contentData.size() == 0 || !util.Arrays.asList(getConfigValue(JsonKey.PROGRAM_ENROLL_ALLOWED_PRIMARY_CATEGORY).split(","): _*).contains(contentData.get(JsonKey.PRIMARYCATEGORY).asInstanceOf[String])))
            ProjectCommonException.throwClientErrorException(ResponseCode.accessDeniedToEnrolOrUnenrolCourse, programId);

        val userIds = request.get(JsonKey.USERID_LIST).asInstanceOf[java.util.List[String]]
        val batchId: String = request.get(JsonKey.BATCH_ID).asInstanceOf[String]
        val batchData: CourseBatch = courseBatchDao.readById(programId, batchId, request.getRequestContext)

        for (userId <- userIds) {
            try {
                var enrolmentData: UserCourses = null
                val enrolmentDataList: java.util.List[UserCourses] = userCoursesDao.readAll(request.getRequestContext, userId, programId)
                if (null != enrolmentDataList) {
                    for (enrolment <- enrolmentDataList) {
                        if (enrolment.isActive) {
                            ProjectCommonException.throwClientErrorException(ResponseCode.userAlreadyEnrolledCourse);
                        }
                        if (enrolment.getBatchId.equals(batchId)) {
                            enrolmentData = enrolment
                        }
                    }
                }
                val batchUserData: BatchUser = batchUserDao.read(request.getRequestContext, batchId, userId)
                validateEnrolment(batchData, enrolmentData, true)
                getCoursesForProgramAndEnrol(request, programId, userId, batchId)
                val dataBatch: util.Map[String, AnyRef] = createBatchUserMapping(batchId, userId, batchUserData)
                val data: java.util.Map[String, AnyRef] = createUserEnrolmentMap(userId, programId, batchId, enrolmentData, request.getContext.getOrDefault(JsonKey.REQUEST_ID, "").asInstanceOf[String])
                upsertEnrollment(userId, programId, batchId, data, dataBatch, (null == enrolmentData), request.getRequestContext)
                logger.info(request.getRequestContext, "ProgramEnrolmentActor :: enroll :: Deleting redis for key " + getCacheKey(userId))
                cacheUtil.delete(getCacheKey(userId))
                generatePreProcessorKafkaEvent(request, batchId, programId, userId)
                generateTelemetryAudit(userId, programId, batchId, data, "enrol", JsonKey.CREATE, request.getContext)
                notifyUser(userId, batchData, JsonKey.ADD)
                status.put(JsonKey.STATUS, JsonKey.SUCCESS)
                response.put(userId, status)
            } catch {
                case e: ProjectCommonException =>
                    if (ResponseCode.userAlreadyEnrolledCourse.getErrorMessage.equals(e.getMessage)) {
                        map.put(JsonKey.STATUS, JsonKey.FAILED)
                        map.put(JsonKey.ERRORMSG, ResponseCode.userAlreadyEnrolledCourse.getErrorMessage)
                        response.put(userId, map)
                    } else {
                        map.put(JsonKey.STATUS, JsonKey.FAILED)
                        map.put(JsonKey.ERRORMSG, e.getMessage)
                        response.put(userId, status)
                    }
                case e: Exception =>
                    map.put(JsonKey.STATUS, JsonKey.FAILED)
                    map.put(JsonKey.ERRORMSG, e.getMessage)
                    response.put(userId, status)
            }
            resp.put(JsonKey.RESPONSE, response)
        }
        sender().tell(resp, self)
    }
}



