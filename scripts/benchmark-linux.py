#!/usr/bin/env python3
import argparse,json,socket,threading,time

p=argparse.ArgumentParser();p.add_argument("workload",choices=["small-tcp","stream","udp","flows"]);p.add_argument("host");p.add_argument("port",type=int);p.add_argument("--seconds",type=float,default=10);p.add_argument("--payload",type=int,default=1400);p.add_argument("--flows",type=int,default=32);a=p.parse_args()
deadline=time.monotonic()+a.seconds;lock=threading.Lock();total_bytes=total_ops=0;latencies=[]
def add(size,latency=None):
 global total_bytes,total_ops
 with lock:
  total_bytes+=size;total_ops+=1
  if latency is not None:latencies.append(latency)
def tcp_stream():
 s=socket.create_connection((a.host,a.port));data=bytes(a.payload)
 try:
  while time.monotonic()<deadline:s.sendall(data);add(len(data))
 finally:s.close()
def small_tcp():
 data=bytes(a.payload)
 while time.monotonic()<deadline:
  start=time.perf_counter_ns();s=socket.create_connection((a.host,a.port));s.sendall(data);s.close();add(len(data),(time.perf_counter_ns()-start)/1000)
def udp():
 family=socket.AF_INET6 if ":" in a.host else socket.AF_INET;s=socket.socket(family,socket.SOCK_DGRAM);s.settimeout(2);data=bytes(a.payload)
 while time.monotonic()<deadline:
  start=time.perf_counter_ns();s.sendto(data,(a.host,a.port));reply,_=s.recvfrom(65535)
  if reply!=data:raise RuntimeError("UDP echo mismatch")
  add(len(data),(time.perf_counter_ns()-start)/1000)
 s.close()
target={"small-tcp":small_tcp,"stream":tcp_stream,"udp":udp,"flows":tcp_stream}[a.workload]
threads=[threading.Thread(target=target) for _ in range(a.flows if a.workload=="flows" else 1)];start=time.monotonic()
for t in threads:t.start()
for t in threads:t.join()
elapsed=time.monotonic()-start;result={"workload":a.workload,"seconds":elapsed,"operations":total_ops,"packets_per_second":total_ops/elapsed,"bytes_per_second":total_bytes/elapsed,"bits_per_second":8*total_bytes/elapsed,"flows":len(threads),"payload_bytes":a.payload}
if latencies:
 latencies.sort();n=len(latencies);result.update({"latency_median_us":latencies[n//2],"latency_p95_us":latencies[min(n-1,int(n*.95))],"latency_p99_us":latencies[min(n-1,int(n*.99))]})
print(json.dumps(result,sort_keys=True))
