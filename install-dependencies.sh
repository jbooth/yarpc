#!/usr/bin/env bash


mvn install:install-file -Dfile=lib/jmxri.jar -DgroupId=com.sun.jmx -DartifactId=jmxri -Dpackaging=jar -Dversion=1.2.1



mvn install:install-file -Dfile=lib/jmxtools.jar -DgroupId=com.sun.jdmk -DartifactId=jmxtools -Dpackaging=jar -Dversion=1.2.1

mvn install:install-file -Dfile=lib/jms-1.1.jar -DgroupId=javax.jms -DartifactId=jms -Dpackaging=jar -Dversion=1.1
