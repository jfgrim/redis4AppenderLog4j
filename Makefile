test:
	redis-server --daemonize yes --port 6379 --pidfile /tmp/redis-test1.pid
	redis-server --daemonize yes --port 7000 --pidfile /tmp/redis-test2.pid
	mvn clean compile test
	kill `cat /tmp/redis-test1.pid`
	kill `cat /tmp/redis-test2.pid`

.PHONY: test
