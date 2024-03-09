A simple java util for bypassing CORS problems on browsers in calling API.

Just launch this utils in background on your pc:

java -jar .\HttpCorsForwarder.jar 8080

then prefix all urls in your fetch with "http://localhost:<choosen_port>", example :

http://localhost:8080/https/jira_server.mycompany.com

and all CORS will be permitted.

(Also, if you need to send cookies and the browser removes it, you can use the fake header "cookies", and that will become "cookie" in the forwarded request)
