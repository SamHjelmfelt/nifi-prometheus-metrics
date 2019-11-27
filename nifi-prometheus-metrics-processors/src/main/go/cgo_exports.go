package main
/*

#cgo CFLAGS: -I/Library/Java/JavaVirtualMachines/jdk1.8.0_71.jdk/Contents/Home/include
#cgo CFLAGS: -I/Library/Java/JavaVirtualMachines/jdk1.8.0_71.jdk/Contents/Home/include/darwin
#include <jni.h>
*/
import "C"
import (
	"github.com/go-kit/kit/log"
	"sync"
)

type channelWriter struct{
	channel chan string
}
func (w channelWriter) Write(p []byte) (n int, err error) {
	w.channel <- string(p[:])
	return len(p), nil
}

//export Java_org_apache_nifi_processors_prometheus_GetPrometheusMetrics_Run
func Java_org_apache_nifi_processors_prometheus_GetPrometheusMetrics_Run(env *C.JNIEnv, callingObject C.jclass, configJStr C.jstring, ID C.jstring, metricQueue C.jobject, logQueue C.jobject) C.int{
    nStr := getNativeStringGo(env, configJStr)
    config := C.GoString(nStr)

	metricChannel := make(chan string, 1000)
	logChannel := make(chan string, 1000)
    jvm := getJVMGo(env)

	logger := log.NewLogfmtLogger(log.NewSyncWriter(channelWriter{logChannel}))

    cancelFunc := GetMetricsWithCancel(config, metricChannel, logger)

	register(C.GoString(getNativeStringGo(env, ID)), cancelFunc)

    addToQueue(jvm, metricQueue, metricChannel, logQueue, logChannel)
    return 0
}
//export Java_org_apache_nifi_processors_prometheus_GetPrometheusMetrics_Stop
func Java_org_apache_nifi_processors_prometheus_GetPrometheusMetrics_Stop(env *C.JNIEnv, callingObject C.jclass, ID C.jstring) C.jboolean {

	cancelFunc, ok := lookup(C.GoString(getNativeStringGo(env, ID)))
	if ok {
		cancelFunc()
		unregister(C.GoString(getNativeStringGo(env, ID)))
	}
	return C.JNI_TRUE
}

var mu sync.Mutex
var fns = make(map[string]func())

func register(ID string, fn func()) {
	mu.Lock()
	defer mu.Unlock()
	fns[ID] = fn
}

func lookup(ID string) (func(),bool) {
	mu.Lock()
	defer mu.Unlock()
	val, ok := fns[ID]
	return val, ok
}

func unregister(ID string) {
	mu.Lock()
	defer mu.Unlock()
	delete(fns, ID)
}

func main() {}