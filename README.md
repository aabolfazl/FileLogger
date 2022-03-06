# FileLogger

The FileLogger is a library for save loges on File with custom formatter on background IO threads, mobile-ready, android
compatible, powered by Java Time library for Android. 

## Features

- Write log file on file
- Working on I/O thread
- Using java FastDateTime
- Support INFO, ERROR, DEBUG, WARNING logging level

## TODO
1. Add C++ NDK support
2. Mail logs
3. Upload on http server
4. Encrypt important logs

## Usage

Init:
```kotlin
val config = Config.Builder(it.path)
    .setDefaultTag("TAG")
    .setLogcatEnable(true)
    .setDataFormatterPattern("dd-MM-yyyy-HH:mm:ss")
    .build()

FileLogger.init(config)
```
Log:
```kotlin
FileLogger.i("TAG", "This is normal Log with custom TAG")
FileLogger.i(msg = "This is normal Info Log")
FileLogger.d(msg = "This is normal Debug Log")
FileLogger.w(msg = "This is normal Warning Log")
FileLogger.e(msg = "This is normal Error Log")
```

Exception:
```kotlin
try {
    //...
} catch (e: Exception) {
    FileLogger.e(msg = "log message", throwable = e)
}
```

Delete log files:
```kotlin
FileLogger.deleteFiles()
```

Enable and disable logging:
```kotlin
FileLogger.setEnable(boolean)
```

## Log Sample
    File logger initialized at 06-03-2022-16:04:51 
    
    06-03-2022-16:05:58 I/Custom: This is normal Log with custom TAG
    06-03-2022-16:05:58 I/TAG: This is normal Info Log
    06-03-2022-16:05:58 D/TAG: This is normal Debug Log
    06-03-2022-16:05:58 W/TAG: This is normal Warning Log
    06-03-2022-16:05:58 E/TAG: This is normal Error Log
    06-03-2022-16:06:00 E/TAG: This is exception
                abbasi.android.filelogger.sample.MainActivity.onCreate$lambda-3(MainActivity.kt:37)
                abbasi.android.filelogger.sample.MainActivity.$r8$lambda$DvDQAirnZLyytJNiMziZSY8Ukuc(Unknown Source:0)
                abbasi.android.filelogger.sample.MainActivity$$ExternalSyntheticLambda0.onClick(Unknown Source:2)
                android.view.View.performClick(View.java:7448)
                com.google.android.material.button.MaterialButton.performClick(MaterialButton.java:1131)
                android.view.View.performClickInternal(View.java:7425)
                android.view.View.access$3600(View.java:810)
                android.view.View$PerformClick.run(View.java:28305)
                android.os.Handler.handleCallback(Handler.java:938)
                android.os.Handler.dispatchMessage(Handler.java:99)
                android.os.Looper.loop(Looper.java:223)
                android.app.ActivityThread.main(ActivityThread.java:7656)
                java.lang.reflect.Method.invoke(Native Method)
                com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:592)
                com.android.internal.os.ZygoteInit.main(ZygoteInit.java:947)


## Installation

Add it in your root build.gradle at the end of repositories:

```gradle
allprojects {
	repositories {
		maven { url 'https://jitpack.io' }
	}
}
```

Add the dependency

```gradle
dependencies { 
    implementation 'com.github.aabolfazl:filelogger:1.0.0'
}
```

License
=======
    MIT License
    Copyright(c) 2022 Abolfazl Abbasi
    
    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:
    
    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.
    
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
