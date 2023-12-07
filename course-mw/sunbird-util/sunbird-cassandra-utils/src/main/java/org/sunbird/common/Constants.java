package org.sunbird.common;

/*
 * @author Amit Kumar
 */
public interface Constants {

  // CASSANDRA CONFIG PROPERTIES
  public static final String CORE_CONNECTIONS_PER_HOST_FOR_LOCAL = "coreConnectionsPerHostForLocal";
  public static final String CORE_CONNECTIONS_PER_HOST_FOR_REMOTE =
      "coreConnectionsPerHostForRemote";
  public static final String MAX_CONNECTIONS_PER_HOST_FOR_LOCAl = "maxConnectionsPerHostForLocal";
  public static final String MAX_CONNECTIONS_PER_HOST_FOR_REMOTE = "maxConnectionsPerHostForRemote";
  public static final String MAX_REQUEST_PER_CONNECTION = "maxRequestsPerConnection";
  public static final String HEARTBEAT_INTERVAL = "heartbeatIntervalSeconds";
  public static final String POOL_TIMEOUT = "poolTimeoutMillis";
  public static final String CONTACT_POINT = "contactPoint";
  public static final String PORT = "port";
  public static final String QUERY_LOGGER_THRESHOLD = "queryLoggerConstantThreshold";
  public static final String CASSANDRA_PROPERTIES_FILE = "cassandra.config.properties";

  // CONSTANT
  public static final String COURSE_ID = "courseId";
  public static final String USER_ID = "userId";
  public static final String BATCH_ID = "batchId";
  public static final String CONTENT_ID = "contentId";
  public static final String IDENTIFIER = "id";
  public static final String SUCCESS = "SUCCESS";
  public static final String RESPONSE = "response";
  public static final String SESSION_IS_NULL = "cassandra session is null for this ";
  public static final String CLUSTER_IS_NULL = "cassandra cluster value is null for this ";
  public static final String QUE_MARK = "?";
  public static final String INSERT_INTO = "INSERT INTO ";
  public static final String OPEN_BRACE_WITH_SPACE = " (";
  public static final String DOT = ".";
  public static final String VALUES_WITH_BRACE = ") VALUES (";
  public static final String COMMA_WITH_SPACE = ", ";
  public static final String CLOSING_BRACE = ");";
  public static final String OPEN_BRACE = "(";
  public static final String COMMA = ",";
  public static final String COMMA_BRAC = "),";
  public static final String UPDATE = "UPDATE ";
  public static final String SET = " SET ";
  public static final String WHERE = " where ";
  public static final String SELECT = "SELECT ";
  public static final String FROM = " FROM ";
  public static final String INCORRECT_DATA = "Incorrect Data";
  public static final String EQUAL = " = ";
  public static final String WHERE_ID = "where id";
  public static final String EQUAL_WITH_QUE_MARK = " = ? ";
  public static final String SEMICOLON = ";";
  public static final String IF_EXISTS = " IF EXISTS;";
  public static final String ALREADY_EXIST = "Record with this primary key already exist.";
  public static final String IF_NOT_EXISTS = " IF NOT EXISTS;";
  public static final String EXCEPTION_MSG_FETCH = "Exception occurred while fetching record from ";
  public static final String EXCEPTION_MSG_UPSERT =
      "Exception occured while upserting record from ";
  public static final String EXCEPTION_MSG_DELETE =
      "Exception occurred while deleting record from ";
  public static final String EXCEPTION_MSG_UPDATE = "Exception occurred while updating record to ";
  public static final String LTE = "<=";
  public static final String LT = "<";
  public static final String GTE = ">=";
  public static final String GT = ">";
  public static final String ID = "id";
  public static final Integer DEFAULT_LIMIT = 250;
  public static final String DATE_FORMAT="yyyy-MM-dd";
  public static final String CONTENT_TYPE = "Content-Type";
  public static final String APPLICATION_JSON = "application/json";
  public static final String OLD_COURSE_BATCH = "oldCourseBatch";
  public static final String UPDATED_COURSE_BATCH = "updatedCourseBatch";
  public static final String REQUEST_CONTEXT = "requestContext";
  public static final String COURSE_NAME = "courseName";
  public static final String BATCH_NAME = "batchName";
  public static final String RECIPIENT_IDS = "recipientUserIds";
  public static final String TRAINING_NAME = "trainingName";
  public static final String START_DATE = "startDate";
  public static final String END_DATE = "endDate";
  public static final String ENROLLMENT_END_DATE = "enrollmentEndDate";
  public static final String BATCH_DATE_UPDATE_TEMPLATE = "batchdateupdatetemplate";
  public static final String EMAIL_BODY = "emailBody";
  public static final String KARMAYOGI_BHARAT = "Karmayogi Bharat";
  public static final String  REQUEST_BODY = "requestBody";
}
