cd bin
echo Main-Class: HttpCorsForwarder> temp_manifest.mf
jar cvfm HttpCorsForwarder.jar temp_manifest.mf *.class