/**
* This file is part of log4j-redis-appender
*
 Copyright 2012 Jef GRIMAULT

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
**/

package client.redis.log4jAppender;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.ErrorCode;
import org.apache.log4j.spi.LoggingEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.util.SafeEncoder;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class RedisAppender extends AppenderSkeleton implements Runnable {

	private String hosts = "localhost:6379";
	private String[] hostPorts;
	private String password;
	private String mode = "channel";
	private String key;
	private boolean enabledDebugging = false;

	//Delay to connect in first time in REDIS in milliseconds
	private long firstDelay = 50;
	//timer sender messages in the REDIS in milliseconds => 50ms for Publishing and 500ms for Pushing with BachSize = 100
	private long period = 100;
	// Parameters for Fault Tolerance and retrying connections
	private int numberRetryToRedis = 2; // the last number of attemptDelay is run two times
	private long attemptDelay = 3000; // in milliseconds

	private final AtomicLong activeRedisNumber = new AtomicLong();
	// Parameters for list mode
	private int batchSize = 100;
	private boolean alwaysBatch = true;
	private boolean purgeOnFailure = true;
	private boolean daemonThread = true;

	private int messageIndex = 0;
	private Queue<LoggingEvent> events;
	private byte[][] batch;

	private Jedis jedis;
	private int retry;
	private ScheduledExecutorService executor;
	private ScheduledFuture<?> task;

	@Override
	public void activateOptions() {
		try {
			super.activateOptions();

			if (key == null) throw new IllegalStateException("Must set 'key'");
			if (!hosts.contains(":")) throw new IllegalStateException("Must set 'hosts' with 'port' value (ex: localhost:6379)");
			// init parameter host:port list
			hostPorts = getHostsPortsList();

			if (executor == null) executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("RedisAppender", daemonThread));
			if (task != null && !task.isDone()) task.cancel(true);
			if (jedis != null && jedis.isConnected()) {
				jedis.disconnect();
				jedis = null; //better for memory
			}

			events = new ConcurrentLinkedQueue<LoggingEvent>();
			batch = new byte[batchSize][];
			messageIndex = 0;
			task = executor.scheduleWithFixedDelay(this, firstDelay, period, TimeUnit.MILLISECONDS);
			LogLog.setInternalDebugging(enabledDebugging);
			LogLog.debug("Initialisation is finish ...");
		}catch (Exception e) {
			LogLog.error("Error during activateOptions", e);
		}
	}

	@Override
	protected void append(LoggingEvent event) {
		try {
			populateEvent(event);
			events.add(event);
		} catch (Exception e) {
			errorHandler.error("Error populating event and adding to queue", e, ErrorCode.GENERIC_FAILURE, event);
		}
	}

	protected void populateEvent(LoggingEvent event) {
		event.getThreadName();
		event.getRenderedMessage();
		event.getNDC();
		event.getMDCCopy();
		event.getThrowableStrRep();
		event.getLocationInformation();
	}

	@Override
	public void close() {
		try {
			if (events.size() > 0) {
				executor.execute(this);
			}
			task.cancel(false);
			executor.shutdown();
			jedis.disconnect();
			jedis = null;
		} catch (Exception e) {
			errorHandler.error(e.getMessage(), e, ErrorCode.CLOSE_FAILURE);
		}
	}

	public void run() {
		if (jedis == null) {
			connect();
		}

		if ("list".equals(mode)) {
            try {
                if (messageIndex == batchSize) push();

                LoggingEvent event;
                while ((event = events.poll()) != null) {
                    try {
                        String message = layout.format(event);
                        batch[messageIndex++] = SafeEncoder.encode(message);
                    } catch (Exception e) {
                        errorHandler.error("Error for set message in list : " + e.getMessage(), e, ErrorCode.GENERIC_FAILURE, event);
                    }

                    if (messageIndex == batchSize) push();
                }

                if (!alwaysBatch && messageIndex > 0) push();
            } catch (Exception e) {
                errorHandler.error("Error for push message : " + e.getMessage(), e, ErrorCode.WRITE_FAILURE);
            }
        } else {
            LoggingEvent event;
            while ((event = events.poll()) != null) {
                try {
                    String message = layout.format(event);
                    publish(message);
                } catch (Exception e) {
                    errorHandler.error("Error for publish message : " + e.getMessage(), e, ErrorCode.WRITE_FAILURE, event);
                }
            }

        }
	}

	// PRIVATE METHOD
	private void connect() {
		try {
			if (jedis != null) jedis.disconnect();

			jedis = null; // in case if jedis is disconnect only
			//Using modulo block to avoid synchronization in which the variable is modified activeJmsBrokerNumber
			int redisNum = (int) (activeRedisNumber.getAndIncrement() % hostPorts.length);
			String[] hostPort = hostPorts[redisNum].split(":");
			String host = hostPort[0];
			int port = Integer.valueOf(hostPort[1]);
			jedis = new Jedis(host, port);
			jedis.connect();

			if (retry > 0) {
				LogLog.warn("[" + host + "] Connection to Redis reestablished after " + retry + " attempt(s)");
			} else {
				LogLog.warn("[" + host + "] Connection to Redis established");
			}

		} catch (Exception e) {
			LogLog.error("Error connecting to Redis server", e);
		}
	}

	/**
	 * Cutting the 'hosts' parameter in String[] ==> ( {localhost:6379},{10.10.56.0:7000} )
	 */
	private String[] getHostsPortsList() {
		String[] hostsPortsList = {hosts};
		if (hosts.contains(",")) {
			hostsPortsList = hosts.split(",");
		}
		return hostsPortsList;
	}

	/**
	 * Expects a delay replay run (). If at the end there is still no connection
	 * we test shipment to another repeat (if there are in settings)
	 */
	private void scheduleReattempt(){
		if (numberRetryToRedis == -1 || retry++ < numberRetryToRedis) {
			task.cancel(false);
			LogLog.debug("[" + retry + "]ieme Retrying connection to Redis in progress ... ");
			pingRedis(); //avoids losses messages
			run();
		} else {
			if (activeRedisNumber.intValue() <= hostPorts.length + 3) {
				LogLog.debug("Retrying after [" + retry + "] attempts connection to Other Redis in progress ... ");
				jedis = null;
				task.cancel(false);
				task = executor.scheduleWithFixedDelay(this,firstDelay,period,TimeUnit.MILLISECONDS);
				retry = 0;
				run();
			} else {
				LogLog.error("Number of attempts for [" + hostPorts.length +"] REDIS after [" + activeRedisNumber.get() +"] crossing, i'm stopping this appender");
				close();
			}
		}
	}

	private void push() {
		try {
			LogLog.debug("Sending " + messageIndex + " log messages to Redis");
			jedis.rpush(SafeEncoder.encode(key),
					batchSize == messageIndex
							? batch
							: Arrays.copyOf(batch, messageIndex));
			messageIndex = 0;
			retry = 0;
		} catch (JedisConnectionException jce) {
			LogLog.error("Error Jedis Connection during sending message with pushing method", jce);
			if (jce.getMessage().contains("closed the connection")) {
				//Connection closed : swith an other redis connection
				LogLog.error("Error with redis server : connection closed");
				jedis = null;
			}
			scheduleReattempt();
		}
	}

	private void publish(String message) {
		try {
			LogLog.debug("Publish " + messageIndex + " log messages to Redis");
			jedis.publish(SafeEncoder.encode(key), SafeEncoder.encode(message));
			retry = 0;
		} catch (JedisConnectionException jce) {
			LogLog.error("Error Jedis Connection during sending message with publishing method", jce);
			if (jce.getMessage().contains("closed the connection")) {
				//Connection closed : swith an other redis connection
				LogLog.error("Error with redis server : connection closed");
				jedis = null;
			}
			scheduleReattempt();
		}
	}

	private void pingRedis() {
		try {
			LogLog.debug("Ping to Redis");
			Thread.sleep(attemptDelay);
			if (jedis == null) {
				connect();
			}
			jedis.ping();
		} catch (JedisConnectionException jce) {
			LogLog.error("Error during ping method", jce);
			scheduleReattempt();
		} catch (InterruptedException e) {
			LogLog.error("Error during ping method with sleep mode", e);
		}
	}

	//SETTER
	public void setHosts(String hosts) {
		this.hosts = hosts;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setPeriod(long millis) {
		this.period = millis;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public void setBatchSize(int batchsize) {
		this.batchSize = batchsize;
	}

	public void setPurgeOnFailure(boolean purgeOnFailure) {
		this.purgeOnFailure = purgeOnFailure;
	}

	public void setAlwaysBatch(boolean alwaysBatch) {
		this.alwaysBatch = alwaysBatch;
	}

	public void setDaemonThread(boolean daemonThread){
		this.daemonThread = daemonThread;
	}

	public boolean requiresLayout() {
		return true;
	}

    public void setMode(String mode) {
        this.mode = mode;
    }

	public void setNumberRetryToRedis(int numberRetryToRedis) {
		this.numberRetryToRedis = numberRetryToRedis;
	}

	public void setAttemptDelay(long attemptDelay) {
		this.attemptDelay = attemptDelay;
	}

	public void setFirstDelay(long firstDelay) {
		this.firstDelay = firstDelay;
	}

	public void setEnabledDebugging(boolean enabledDebugging) {
		this.enabledDebugging = enabledDebugging;
	}
}
