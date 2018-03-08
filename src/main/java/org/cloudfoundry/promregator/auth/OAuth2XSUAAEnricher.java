package org.cloudfoundry.promregator.auth;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.http.Consts;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.cloudfoundry.promregator.config.OAuth2XSUAAAuthenticationConfiguration;

import com.google.gson.Gson;

public class OAuth2XSUAAEnricher implements AuthenticationEnricher {
	
	private static final Logger log = Logger.getLogger(OAuth2XSUAAEnricher.class);
	
	final static CloseableHttpClient httpclient = HttpClients.createDefault();

	final private OAuth2XSUAAAuthenticationConfiguration config;
	
	public OAuth2XSUAAEnricher(OAuth2XSUAAAuthenticationConfiguration config) {
		super();
		this.config = config;
	}

	@Override
	public void enrichWithAuthentication(HttpGet httpget) {
		RequestConfig config = httpget.getConfig();
		
		String jwt = getBufferedJWT(httpget.getURI(), config);
		if (jwt == null) {
			log.error("Unable to enrich request with JWT");
			return;
		}
		
		httpget.setHeader("Authorization", String.format("Bearer %s", jwt));
	}

	private String bufferedJwt = null;
	private Instant validUntil = null;
	
	private synchronized String getBufferedJWT(URI uri, RequestConfig config) {
		// TODO: Buffering must happen based on the uri (there could be multiple one's!)
		
		if (this.bufferedJwt == null) {
			this.bufferedJwt = getJWT(config);
		} else if (Instant.now().isAfter(this.validUntil)) {
			this.bufferedJwt = getJWT(config);
		}
		
		return bufferedJwt;
	}

	private String getJWT(RequestConfig config) {
		log.info("Fetching new JWT token");
		
		String url = String.format("%s?grant_type=client_credentials", this.config.getTokenServiceURL());
		if (this.config.getScopes() != null) {
			// see also https://www.oauth.com/oauth2-servers/access-tokens/client-credentials/
			try {
				url += String.format("&scope=%s", URLEncoder.encode(this.config.getScopes(), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				log.error("Error while adding scope information to request URL", e);
				return null;
			}
		}
		
		HttpPost httpPost = new HttpPost(url);
		httpPost.setConfig(config);
		
		if (this.config.getClient_id().contains(":")) {
			throw new Error("Security: jwtClient_id contains colon");
		}

		if (this.config.getClient_secret().contains(":")) {
			throw new Error("Security: jwtClient_id contains colon");
		}
		
		String b64encoding = String.format("%s:%s", this.config.getClient_id(), this.config.getClient_secret());
		
		byte[] encodedBytes = null;
		try {
			encodedBytes = b64encoding.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			log.error("Unable to b64-encode using UTF-8", e);
			return null;
		}
		String encoding = Base64.getEncoder().encodeToString(encodedBytes);
		
		httpPost.setHeader("Authorization", String.format("Basic %s", encoding));
		httpPost.setHeader("Content-Type", "application/json");

		/* closing the connection afterwards is important!
		 * Background: httpclient will otherwise try to keep the connection open.
		 * We won't be calling often anyway, so the server would be drained from resources.
		 * Moreover, if the server has gone away in the meantime, the next attempt to 
		 * call would fail with a recv error when reading from the socket.
		 */
		httpPost.setHeader("Connection", "close");
		
		List<NameValuePair> form = new ArrayList<>();
		form.add(new BasicNameValuePair("response_type", "token"));
		UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(form, Consts.UTF_8);
		httpPost.setEntity(formEntity);
		
		CloseableHttpResponse response = null;
		String json = null;
		try {
			try {
				response = httpclient.execute(httpPost);
			} catch (ClientProtocolException e) {
				log.error("Unable to read from the token server", e);
				return null;
			} catch (IOException e) {
				log.error("IO Exception while reading from the token server", e);
				return null;
			}
			
			if (response.getStatusLine().getStatusCode() != 200) {
				log.error(String.format("Server did not respond with ok while fetching JWT from token server; status code provided: %d", response.getStatusLine().getStatusCode()));
				return null;
			}
			
			try {
				json = EntityUtils.toString(response.getEntity(), "UTF-8");
			} catch (ParseException e) {
				log.error("GSON parser exception on JWT response from token server", e);
				return null;
			} catch (IOException e) {
				log.error("IO Exception while running GSON parser", e);
				return null;
			}
		} finally {
			if (response != null) {
				try {
					response.close();
				} catch (IOException e) {
					log.info("Unable to properly close JWT-retrieval connection", e);
				}
			}
		}
		Gson gson = new Gson();
		TokenResponse jwtResponse = gson.fromJson(json, TokenResponse.class);
		
		int timeOutForUs = Math.max(jwtResponse.getExpiresIn() - 30, jwtResponse.getExpiresIn() / 2);
		this.validUntil = Instant.now().plus(timeOutForUs, ChronoUnit.SECONDS);
		
		log.info("JWT token retrieved");
		
		return jwtResponse.getAccessToken();
	}

	private static class TokenResponse {
		private String access_token;
		private int expires_in;
		
		public String getAccessToken() {
			return access_token;
		}

		public int getExpiresIn() {
			return expires_in;
		}
	}
	
}
