# redis4AppenderLog4j

Log4j appender for pushing into a list or publish log messages to a Redis.

Derived from [@ryantenney/log4j-redis-appender](https://github.com/ryantenney/log4j-redis-appender). That project writes messages with a PUBLISH modes (channel).

## Configuration

This appender pushes log messages to a Redis list. Here is an example configuration:

    log4j.rootLogger=DEBUG, redis
    log4j.appender.redis=RedisAppender
    log4j.appender.redis.layout=…
    log4j.appender.redis.hosts=localhost:6379,10.10.3.3:7000
    log4j.appender.redis.password=password
    log4j.appender.redis.mode=list
    log4j.appender.redis.key=key
    log4j.appender.redis.period=500
    log4j.appender.redis.batchSize=100
    log4j.appender.redis.purgeOnFailure=true
    log4j.appender.redis.alwaysBatch=true
    log4j.appender.redis.daemonThread=true

Or in log4j.xml format

       <appender name="redis" class="client.redis.log4jAppender.RedisAppender">
                <param name="key" value="key" />
                <param name="hosts" value="localhost:6379,10.10.3.3:7000" />
                <param name="mode" value="channel" />
                <param name="firstDelay" value="250" />
                <param name="batchSize" value="100" />
                <param name="attemptDelay" value="2000" />
                <param name="numberRetryToRedis" value="3" />
                <param name="period" value="150" />
                <param name="alwaysBatch" value="true" />
                <layout class="org.apache.log4j.PatternLayout">
                        <param name="ConversionPattern" value="%d{ISO8601} %-5p %c %x - [vm02-account] - (%X{userId}) (%X{authToken}) (%X{request}) (%X{sessionId}) - %m  %n" />
                </layout>
        </appender>

       <logger name="com.application" additivity="false">
                <level value="debug" />
                <appender-ref ref="redis" />
        </logger>
or
       <root>
                <priority value="info" />
                <appender-ref ref="redis" />
                <appender-ref ref="console" />
        </root>


Where:

* **key** (_required_) key of the list to push or publish log messages
* **hosts** (optional, default: localhost:6379) name (or ip) and port for connection in host
* **password** (optional) redis password, if required
* **period** (optional, default: 500) the period in milliseconds between each send messages
* **batchSize** (optional, default: 100) the number of log messages to send in a single `RPUSH` command
* **purgeOnFailure** (optional, default: true) whether to purge the enqueued log messages if an error occurs attempting to connect to redis, thus preventing the memory usage from becoming too high
* **alwaysBatch** (optional, default: true) whether to wait for a full batch. if true, will only send once there are `batchSize` log messages enqueued
* **daemonThread** (optional, default: true) whether to launch the appender thread as a daemon thread
* **attemptDelay** (optional, default: 2000) period of time between each attempt to connect to REDIS
* **numberRetryToRedis** (optional, default: 2) number of connection test to REDIS

### Maven

```xml
<dependency>
	<groupId>client.redis.log4jAppender</groupId>
	<artifactId>redis4Log4j</artifactId>
	<version>1.0.0</version>
</dependency>
```

### Usage Note

Goes great with [@lusis's log4j-jsonevent-layout](https://github.com/lusis/log4j-jsonevent-layout) for pushing log messages straight to a [Logstash](https://github.com/logstash/logstash) instance configured to ingest log messages from Redis. If you use Logstash with AMQP, check out [@lusis's ZeroMQ Appender](https://github.com/lusis/zmq-appender) or [@jbrisbin's RabbitMQ Appender](https://github.com/jbrisbin/vcloud/tree/master/amqp-appender)

### License

Copyright (c) 2014 JeF Grimault

Published under Apache Software License 2.0, see LICENSE

[![Rochester Made](http://rochestermade.com/media/images/rochester-made-dark-on-light.png)](http://rochestermade.com)
