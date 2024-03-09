A simple java util for bypassing CORS problems on browsers in calling API.
Just launch this utils on your pc:
java -jar .\HttpCorsForwarder.jar 8080
then prefix all urls in your fetch with localhost and choosen port:
http://localhost:8080/https/jira_server.mycompany.com
and all CORS will be permitted.
