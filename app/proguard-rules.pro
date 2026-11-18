# JGit memakai reflection pada beberapa kelas internal.
-keep class org.eclipse.jgit.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.xz.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-keep class com.termux.githubuploader.** { *; }