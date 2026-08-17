#include <jni.h>
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_mbuf.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct wf_handle { uint16_t port, rxq, txq; struct rte_mempool *pool; };

JNIEXPORT jlong JNICALL Java_io_github_shri299_wirefin_device_DpdkDevice_nativeOpen
  (JNIEnv *env, jclass cls, jobjectArray arguments, jint port, jint rxq, jint txq,
   jint rxd, jint txd, jint mbufs, jint frame_size) {
    (void)cls; (void)frame_size;
    jsize argc = (*env)->GetArrayLength(env, arguments); char **argv = calloc((size_t)argc + 1, sizeof(char*));
    if (!argv) return 0;
    for (jsize i=0;i<argc;i++) { jstring value=(jstring)(*env)->GetObjectArrayElement(env,arguments,i); const char *utf=(*env)->GetStringUTFChars(env,value,0); argv[i]=strdup(utf); (*env)->ReleaseStringUTFChars(env,value,utf); }
    int eal = rte_eal_init(argc, argv); for (jsize i=0;i<argc;i++) free(argv[i]); free(argv); if (eal < 0 || !rte_eth_dev_is_valid_port((uint16_t)port)) return 0;
    struct wf_handle *h = calloc(1,sizeof(*h)); if (!h) return 0; h->port=port; h->rxq=rxq; h->txq=txq;
    char name[64]; snprintf(name,sizeof(name),"wirefin_%d",port);
    h->pool=rte_pktmbuf_pool_create(name,(unsigned)mbufs,256,0,RTE_MBUF_DEFAULT_BUF_SIZE,rte_socket_id());
    struct rte_eth_conf conf={0};
    if (!h->pool || rte_eth_dev_configure(h->port,rxq+1,txq+1,&conf)<0 ||
        rte_eth_rx_queue_setup(h->port,h->rxq,(uint16_t)rxd,rte_eth_dev_socket_id(h->port),NULL,h->pool)<0 ||
        rte_eth_tx_queue_setup(h->port,h->txq,(uint16_t)txd,rte_eth_dev_socket_id(h->port),NULL)<0 ||
        rte_eth_dev_start(h->port)<0) { free(h); return 0; }
    rte_eth_promiscuous_enable(h->port); return (jlong)(uintptr_t)h;
}

JNIEXPORT jint JNICALL Java_io_github_shri299_wirefin_device_DpdkDevice_nativeReceive
  (JNIEnv *env,jclass cls,jlong pointer,jobject arena,jint stride,jintArray sizes,jint max_packets) {
    (void)cls; struct wf_handle *h=(struct wf_handle*)(uintptr_t)pointer; uint8_t *base=(*env)->GetDirectBufferAddress(env,arena);
    if (!h || !base || max_packets<1 || max_packets>256) return -1;
    struct rte_mbuf *packets[256]; uint16_t count=rte_eth_rx_burst(h->port,h->rxq,packets,(uint16_t)max_packets);
    jint lengths[256]; uint16_t kept=0;
    for (uint16_t i=0;i<count;i++) { uint32_t length=rte_pktmbuf_pkt_len(packets[i]); if (length<=(uint32_t)stride && rte_pktmbuf_read(packets[i],0,length,base+(size_t)kept*stride)) lengths[kept++]=(jint)length; rte_pktmbuf_free(packets[i]); }
    (*env)->SetIntArrayRegion(env,sizes,0,kept,lengths); return kept;
}

JNIEXPORT jint JNICALL Java_io_github_shri299_wirefin_device_DpdkDevice_nativeTransmit
  (JNIEnv *env,jclass cls,jlong pointer,jobject arena,jint stride,jintArray sizes,jint count) {
    (void)cls; struct wf_handle *h=(struct wf_handle*)(uintptr_t)pointer; uint8_t *base=(*env)->GetDirectBufferAddress(env,arena);
    if (!h || !base || count<0 || count>256) return -1; jint lengths[256]; (*env)->GetIntArrayRegion(env,sizes,0,count,lengths);
    struct rte_mbuf *packets[256]; int prepared=0;
    for (;prepared<count;prepared++) { packets[prepared]=rte_pktmbuf_alloc(h->pool); if (!packets[prepared]) break; void *target=rte_pktmbuf_append(packets[prepared],(uint16_t)lengths[prepared]); if (!target) { rte_pktmbuf_free(packets[prepared]); break; } memcpy(target,base+(size_t)prepared*stride,(size_t)lengths[prepared]); }
    uint16_t sent=rte_eth_tx_burst(h->port,h->txq,packets,(uint16_t)prepared); for (int i=sent;i<prepared;i++) rte_pktmbuf_free(packets[i]); return sent;
}

JNIEXPORT void JNICALL Java_io_github_shri299_wirefin_device_DpdkDevice_nativeClose
  (JNIEnv *env,jclass cls,jlong pointer) { (void)env;(void)cls; struct wf_handle *h=(struct wf_handle*)(uintptr_t)pointer; if (!h) return; rte_eth_dev_stop(h->port); rte_eth_dev_close(h->port); free(h); }
