package org.sunbird.cache.util

import java.time.Duration

import org.apache.commons.lang3.StringUtils
import org.sunbird.cache.platform.Platform
import org.sunbird.common.models.util.LoggerUtil
import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/**
  * This Utility Object Provide Methods To Perform CRUD Operation With Redis
  */
class RedisCacheUtil {

    private val logger: LoggerUtil = new LoggerUtil(classOf[RedisCacheUtil])

    implicit val className = "org.sunbird.cache.connector.RedisConnector"

    private val redis_host = Platform.getString("sunbird_redis_host", "localhost")
    private val redis_port = Platform.getInteger("sunbird_redis_port", 6379)
    private val index = Platform.getInteger("redis.dbIndex", 0)

     println("=====redis_host=====" + redis_host)
    println("=====redis index=====" + index)
    println("=====redis port=====" + redis_port)
    private def buildPoolConfig = {
        val poolConfig = new JedisPoolConfig
        poolConfig.setMaxTotal(Platform.getInteger("redis.connection.max", 2))
        poolConfig.setMaxIdle(Platform.getInteger("redis.connection.idle.max", 2))
        poolConfig.setMinIdle(Platform.getInteger("redis.connection.idle.min", 1))
        poolConfig.setTestWhileIdle(true)
        poolConfig.setMinEvictableIdleTimeMillis(Duration.ofSeconds(Platform.getLong("redis.connection.minEvictableIdleTimeSeconds", 120)).toMillis)
        poolConfig.setTimeBetweenEvictionRunsMillis(Duration.ofSeconds(Platform.getLong("redis.connection.timeBetweenEvictionRunsSeconds", 300)).toMillis)
        poolConfig.setBlockWhenExhausted(true)
        poolConfig
    }

    protected var jedisPool: JedisPool = new JedisPool(buildPoolConfig, redis_host, redis_port)

    def getConnection(database: Int): Jedis = {
        val conn = jedisPool.getResource
        conn.select(database)
        conn
    }

    def getConnection: Jedis = try {
        val jedis = jedisPool.getResource
        if (index > 0) jedis.select(index)
        jedis
    } catch {
        case e: Exception => throw e
    }

    /**
      * This Method takes a connection object and put it back to pool.
      *
      * @param jedis
      */
    protected def returnConnection(jedis: Jedis): Unit = {
        try if (null != jedis) jedisPool.returnResource(jedis)
        catch {
            case e: Exception => throw e
        }
    }


    def resetConnection(): Unit = {
        jedisPool.close()
        jedisPool = new JedisPool(buildPoolConfig, redis_host, redis_port)
    }

    def closePool() = {
        jedisPool.close()
    }

    def checkConnection = {
        try {
            val conn = getConnection(2)
            conn.close()
            true;
        } catch {
            case ex: Exception => false
        }
    }

    /**
      * This method store string data into cache for given Key
      *
      * @param key
      * @param data
      * @param ttl
      */
    def set(key: String, data: String, ttl: Int = 0): Unit = {
        val jedis = getConnection
        try {
            jedis.del(key)
            jedis.set(key, data)
            if (ttl > 0) jedis.expire(key, ttl)
        } catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Saving String Data to Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method read string data from cache for a given key
      *
      * @param key
      * @param ttl
      * @param handler
      * @return
      */
    def get(key: String, handler: (String) => String = defaultStringHandler, ttl: Int = 0): String = {
        val jedis = getConnection
        try {
            var data = jedis.get(key)
            if (null != handler && (null == data || data.isEmpty)) {
                data = handler(key)
                if (null != data && !data.isEmpty)
                    set(key, data, ttl)
            }
            data
        }
        catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Fetching String Data from Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This Method Returns Future[String] for given key
      *
      * @param key
      * @param asyncHandler
      * @param ttl
      * @param ec
      * @return Future[String]
      */
    def getAsync(key: String, asyncHandler: (String) => Future[String], ttl: Int = 0)(implicit ec: ExecutionContext): Future[String] = {
        val jedis = getConnection
        try {
            val data = jedis.get(key)
            if (null != asyncHandler && (null == data || data.isEmpty)) {
                val dataFuture: Future[String] = asyncHandler(key)
                dataFuture.map(value => {
                    if (null != value && !value.isEmpty)
                        set(key, value, ttl)
                    value
                })
            } else Future {
                data
            }
        }
        catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Fetching String Data from Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method increment the value by 1 into cache for given key and returns the new value
      *
      * @param key
      * @return Double
      */
    def incrementAndGet(key: String): Double = {
        val jedis = getConnection
        val inc = 1.0
        try jedis.incrByFloat(key, inc)
        catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Incrementing Value for Key : " + key + " | Exception is : ", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method store/save list data into cache for given Key
      *
      * @param key
      * @param data
      * @param isPartialUpdate
      * @param ttl
      */
    def saveList(key: String, data: List[String], ttl: Int = 0, isPartialUpdate: Boolean = false): Unit = {
        val jedis = getConnection
        try {
            if (!isPartialUpdate)
                jedis.del(key)
            data.foreach(entry => jedis.sadd(key, entry))
            if (ttl > 0 && !isPartialUpdate) jedis.expire(key, ttl)
        } catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Saving List Data to Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method store/save list data into cache for given Key
      *
      * @param key
      * @param data
      */
    def addToList(key: String, data: List[String]): Unit = {
        saveList(key, data, 0, true)
    }

    /**
      * This method returns list data from cache for a given key
      *
      * @param key
      * @param handler
      * @param ttl
      * @return
      */
    def getList(key: String, handler: (String) => List[String] = defaultListHandler, ttl: Int = 0, index: Int = 0): List[String] = {
        val jedis = getConnection(index)
        try {
            var data = jedis.smembers(key).asScala.toList
            if (null != handler && (null == data || data.isEmpty)) {
                data = handler(key)
                if (null != data && !data.isEmpty)
                    saveList(key, data, ttl, false)
            }
            data
        } catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Fetching List Data from Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method returns list data from cache for a given key
      *
      * @param key
      * @param asyncHandler
      * @param ttl
      * @param ec
      * @return Future[List[String]]
      **/
    def getListAsync(key: String, asyncHandler: (String) => Future[List[String]], ttl: Int = 0)(implicit ec: ExecutionContext): Future[List[String]] = {
        val jedis = getConnection
        try {
            val data = jedis.smembers(key).asScala.toList
            if (null != asyncHandler && (null == data || data.isEmpty)) {
                val dataFuture = asyncHandler(key)
                dataFuture.map(value => {
                    if (null != value && !value.isEmpty)
                        saveList(key, value, ttl, false)
                    value
                })
            } else Future {
                data
            }
        } catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Fetching List Data from Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This Method Remove Given Data From Existing List For Given Key
      *
      * @param key
      * @param data
      */
    def removeFromList(key: String, data: List[String]): Unit = {
        val jedis = getConnection
        try data.foreach(entry => jedis.srem(key, entry))
        catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Deleting Partial Data From Redis Cache for Key : " + key + "| Exception is:", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method delete data from cache for given key/keys
      *
      * @param keys
      */
    def delete(keys: String*): Unit = {
        val jedis = getConnection
        try jedis.del(keys.map(_.asInstanceOf[String]): _*)
        catch {
            case e: Exception =>
                logger.error(null, "Exception Occurred While Deleting Records From Redis Cache for Identifiers : " + keys.toArray + " | Exception is : ", e)
                throw e
        } finally returnConnection(jedis)
    }

    /**
      * This method delete data from cache for all key/keys matched with given pattern
      *
      * @param pattern
      */
    def deleteByPattern(pattern: String): Unit = {
        if (StringUtils.isNotBlank(pattern) && !StringUtils.equalsIgnoreCase(pattern, "*")) {
            val jedis = getConnection
            try {
                val keys = jedis.keys(pattern)
                if (keys != null && keys.size > 0)
                    jedis.del(keys.toArray.map(_.asInstanceOf[String]): _*)
            } catch {
                case e: Exception =>
                    logger.error(null, "Exception Occurred While Deleting Records From Redis Cache for Pattern : " + pattern + " | Exception is : ", e)
                    throw e
            } finally returnConnection(jedis)
        }
    }

    private def defaultStringHandler(objKey: String): String = {
        //Default Implementation Can Be Provided Here
        ""
    }

    private def defaultListHandler(objKey: String): List[String] = {
        //Default Implementation Can Be Provided Here
        List()
    }

    def getList(key: String, index: Int): List[String] = getList(key, defaultListHandler, 0, index)
}
