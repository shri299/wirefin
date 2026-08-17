JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
CFLAGS += -O3 -fPIC -Wall -Wextra $(shell pkg-config --cflags libdpdk) -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux
LDLIBS += $(shell pkg-config --libs libdpdk)

all: libwirefin_dpdk.so

libwirefin_dpdk.so: wirefin_dpdk.c
	$(CC) $(CFLAGS) -shared -o $@ $< $(LDLIBS)

clean:
	$(RM) libwirefin_dpdk.so
