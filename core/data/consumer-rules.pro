# JSch creates ciphers, key exchanges and signatures by class name from its configuration
# (JSch.getConfig, e.g. "com.jcraft.jsch.bc.XDH"), so R8 cannot see they are used.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch integrations that are not shipped (logging bridges, ssh-agent over unix sockets).
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**
