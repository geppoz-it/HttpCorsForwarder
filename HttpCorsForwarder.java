import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

class HttpCorsForwarder implements Runnable {

	Socket _i;

  Hashtable<String,String> passHeaders=new Hashtable<>();

	public void run(){
    try {
      InputStream is=_i.getInputStream();
      OutputStream os=_i.getOutputStream();
      do {
        // read input request
        StringBuffer txt=new StringBuffer();
        int l;
        byte[] buffer=new byte[64000];
        long t=System.currentTimeMillis();
        String gotHeader=null; int ixHeader=-1,contentLength=0;
        while ((is.available()>0)||(System.currentTimeMillis()-t<5000)){ // se richiesta non arriva entro un timeout esce
          if ((l=is.available())>0){
            if ((l=is.read(buffer))!=-1) txt.append(new String(buffer,0,l));
            //
            if (gotHeader==null){ // verifica se l'header è completo
              if ((ixHeader=txt.indexOf("\r\n\r\n"))!=-1){ // se è completato l'header
                gotHeader=txt.substring(0,ixHeader);  
                String[] s=_getRegexGroups("(?si).*\\r?\\nContent-Length\\s*:\\s*(\\d+)\\r?\\n.*",gotHeader);
                if (s!=null) contentLength=Integer.parseInt(s[0]);
                else { // se non è specificato Content-Lenght allora considera terminati i casi GET, OPTIONS, ...
                  if (gotHeader.startsWith("GET ")||gotHeader.startsWith("OPTIONS ")) break; // no payload expected
                }
              }
            }
            if ((contentLength!=0)&&(txt.length()-ixHeader-4==contentLength)) break; // nota: da verificare cosa succede per caratteri multibyte...
          } else {
            Thread.sleep(100);
          }
        }
        if (txt.length()==0) break;
        //String[] headAndBody=(""+txt).split("\n\n");
        System.out.println("Received request: raw:----------\n"+txt+"\n--------------------------\n");
        String[] headAndBody=_getRegexGroups("(?s)^(.*?)\\r?\\n\\r?\\n(.*)$",""+txt);  // 0=head 1=body
        String[] headers=headAndBody[0].split("\r?\n");
        System.out.println("Received request: parsed:----------");
        String[] methodAndUrl=_getRegexGroups("^(.*?) (.*) HTTP/.*$",headers[0]);
        String method=methodAndUrl[0];
        String urlReq=methodAndUrl[1];
        System.out.println("method: "+method);
        System.out.println("url: "+urlReq);
        for (int i=1;i<headers.length;i++){
          String[] kv=headers[i].split(": ",2);
          System.out.println("header: "+kv[0]+" = "+kv[1]);
        }
        String body=headAndBody[1];
        System.out.println("body: "+body);
        System.out.println("parsed:----------\n");

        String[] realUrlReq=_getRegexGroups("^/(https?)(/.*)$",urlReq);
        urlReq=realUrlReq[0]+":/"+realUrlReq[1];
        ArrayList<String> headersToSend=new ArrayList<>();
        for (int i=1;i<headers.length;i++){
          String[] kv=headers[i].split(": ",2);
          if (kv[0].equalsIgnoreCase("cookies")) kv[0]="Cookie"; // permette il passaggio di "cookie" tramite "cookies"
          if (passHeaders.get(kv[0].toLowerCase())!=null){
            headersToSend.add(kv[0]+": "+kv[1]);
          }
        }
        String[] Headers=new String[headersToSend.size()];
        Headers=(String[])headersToSend.toArray(Headers);
        
        if (method.equals("OPTIONS")){
          System.out.println("Stubbing OPTIONS");
          os.write(("HTTP/1.1 204 No Content\r\n"+
            "Access-Control-Allow-Origin: *\r\n"+
            "Access-Control-Allow-Methods: POST, PUT, GET, OPTIONS\r\n"+
            "Access-Control-Allow-Headers: *\r\n"+
            "Access-Control-Max-Age: 86400\r\n"+
            "\r\n"
          ).getBytes());
        } else {
          System.out.println("Calling endpoint "+urlReq+"...");
          String[] out=post(method,urlReq,Headers,body);
          System.out.println("...done");
          if (out[0]!=null){
            System.out.println("Responding to caller:"+out[0]+" "+out[1]);
            os.write(("HTTP/1.1 "+out[0]+"\r\n"+
              "Connection: Keep-Alive\r\n"+
              "Access-Control-Allow-Origin: *\r\n"+
              "Content-Type: application/json\r\n"+
              "Content-Length: "+out[1].length()+"\r\n"+
              "\r\n"+out[1]).getBytes()
            );
          } else {
            System.out.println("Responding error:"+out[1]);
            os.write(("HTTP/1.1 500 Internal server error\r\n"+
              "Connection: Keep-Alive\r\n"+
              "Content-Type: application/json\r\n"+
              "\r\n"+out[1]).getBytes()
            );
          }
        }
      } while(!_i.isClosed());
      _i.close();
    } catch (Exception e){ e.printStackTrace(); }
    System.out.println("Exiting from runner...");
  }


	void _listen_and_spawn(int _p,boolean _debugpanel){
		ServerSocket _so;
		try {
			_so=new ServerSocket(_p);
		} catch (IOException e1) {
			System.out.println("(LISTENER) Unable to bind...");
			return;
		}
		while (true){
			Socket _a=null;
			try {
				System.out.println("(LISTENER) Listening for connections...");
				_a=_so.accept();
				_a.setKeepAlive(true);
				System.out.println("(LISTENER) Received connection from "+_a.getInetAddress()+":"+_a.getPort());
				HttpCorsForwarder nws1=new HttpCorsForwarder();
				nws1._i=_a;
        nws1.passHeaders=this.passHeaders;
				new Thread(nws1).start();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			try {
				//_a.close();
			} catch (Exception e) { e.printStackTrace(); }
		}
	}

	// ritorna i gruppi catturati, altrimenti null 
	static String[] _getRegexGroups(String p,String s){
		Pattern _p=Pattern.compile(p);
		Matcher _m=_p.matcher(s);
		if (_m.matches()){
			String[] r=new String[_m.groupCount()];
			for (int i=0;i<r.length;i++) r[i]=_m.group(i+1);
			return r;
		}
		return null;
	}


//////

  // ritorna [ <codice di risposta o null se eccezione> , <payload o errore> ]
  public static String[] post(String method,String url,String[] headers,String payload){
    StringBuffer txt=new StringBuffer();
    try {
      URL u = new URL(url);
      HttpURLConnection conn = (HttpURLConnection) u.openConnection();
      conn.setRequestMethod(method);
      for (int i=0;i<headers.length;i++){
        String[] kv=headers[i].split(": ",2);
        conn.setRequestProperty( kv[0],kv[1]);
      };
      if (method.equals("POST")||method.equals("PUT")){
        conn.setDoOutput(true);
        conn.setRequestProperty( "Content-Length", String.valueOf(payload.length()));
        OutputStream os = conn.getOutputStream();
        os.write(payload.getBytes());
      }
      try {
        InputStream is=conn.getInputStream();
        int l;
        byte[] buffer=new byte[2000];
        while ((l=is.read(buffer))!=-1) txt.append(new String(buffer,0,l));
      } catch (Exception e1){
        System.err.println("Error reading input stream...");
        try {
          InputStream is=conn.getErrorStream();
          int l;
          byte[] buffer=new byte[2000];
          while ((l=is.read(buffer))!=-1) txt.append(new String(buffer,0,l));
        } catch (Exception e2){
          System.err.println("Error reading error stream...");
        }
      }
      return new String[]{ conn.getResponseCode()+" "+conn.getResponseMessage() , txt+"" };
      //if (conn.getResponseCode()==200){
      //    return new String[]{txt+"",null};
      //}
      
      //return new String[]{null,conn.getResponseMessage()+txt};
      //String s=conn.getResponseMessage();
      //System.out.println(s);
    } catch(Exception _e){
      _e.printStackTrace();
      return new String[]{null,"Exception:"+_e};
    }
  }

	public static void main(String[] a){
		int _port=-1;
		if (a.length>0){
			try{
				_port=Integer.parseInt(a[0]);
			} catch(Exception e){}
		}
		if (_port==-1) System.out.println("Utility for bypassing CORS problems calling API in browsers.\n"+
      "It listen on localhost on the port specified, then forwards every request received to the endpoint specified as prefix in the url.\n"+
      "Example: http://localhost:8080/https/www.geppoz.eu/index.html\n\n"+
      "Expected one argument: listening_port");
		else {
			System.out.println("Starting service on port "+_port);
			HttpCorsForwarder nws=new HttpCorsForwarder();

      try {
        TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
          public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
          public void checkClientTrusted(X509Certificate[] certs, String authType) {        }
          public void checkServerTrusted(X509Certificate[] certs, String authType) {        }
        }};
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
      } catch (Exception e) {
        e.printStackTrace();
      }
      nws.passHeaders.put("Content-Type".toLowerCase(), "application/json" );
      nws.passHeaders.put("Accept".toLowerCase(),"application/json");
      nws.passHeaders.put("Authorization".toLowerCase(),"");
      nws.passHeaders.put("Cookie".toLowerCase(),"");
      System.out.println(nws.passHeaders);

			nws._listen_and_spawn(_port,(a.length>1)&&(a[1].equalsIgnoreCase("--debug")));
		}
	}

};
