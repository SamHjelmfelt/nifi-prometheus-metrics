package main
/*
#cgo CFLAGS: -I/Library/Java/JavaVirtualMachines/jdk1.8.0_71.jdk/Contents/Home/include
#cgo CFLAGS: -I/Library/Java/JavaVirtualMachines/jdk1.8.0_71.jdk/Contents/Home/include/darwin
#include <jni.h>
#include <stdbool.h>    
#include <stdio.h>
#include <stdlib.h>
JavaVM* getJVM(JNIEnv* env){
  JavaVM* jvm;
  (*env)->GetJavaVM(env, &jvm);
  return jvm;
}
char* getNativeString(JNIEnv* env, jstring str) {
   return (char*)((*env)->GetStringUTFChars(env, str, 0));
}
void addToQueue(JavaVM* jvm, jobject queue, char* bytes) {
  JNIEnv* env;
  //go does not have thread affinity, so re-attach everytime
  (*jvm)->AttachCurrentThread(jvm, (void**)&env, NULL); 
  jclass queueClass = (*env)->GetObjectClass(env, queue);
  jmethodID putMethod = (*env)->GetMethodID(env, queueClass, "put", "(Ljava/lang/Object;)V");
  (*env)->DeleteLocalRef(env, queueClass);

  jstring str = (*env)->NewStringUTF(env, bytes);
  (*env)->CallVoidMethod(env, queue, putMethod, str);
  (*env)->DeleteLocalRef(env, str);
}
*/
import "C"
import "time"

func getNativeStringGo(env *C.JNIEnv, str C.jstring) *C.char{
  return C.getNativeString(env,str);
}
func getJVMGo(sourceEnv *C.JNIEnv) *C.JavaVM{
  jvm := C.getJVM(sourceEnv);
  return jvm
}
func addToQueue(jvm *C.JavaVM, metricQueue C.jobject, metricChannel chan string, logQueue C.jobject, logChannel chan string) {

  for {
    //Check for logs before pulling metrics
    select {
      case log := <- logChannel:
      	if log != "" {
			C.addToQueue(jvm, logQueue, C.CString(log))
		}
      default:
        //No log messages
    }

    hasMetrics := true
    for hasMetrics == true { //Loop while metrics are available
      select {
		  case metric := <- metricChannel:
			  if metric != "" {
				  C.addToQueue(jvm, metricQueue, C.CString(metric))
			  }
		  default:
			//No metrics
			hasMetrics = false
      }
    }
    time.Sleep(100 * time.Millisecond)
  }
}
