/*******************************************************************************
 * Copyright 2015 The MITRE Corporation
 *   and the MIT Kerberos and Internet Trust Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
/**
 * 
 */
package org.mitre.openid.connect.client.service.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.client.HttpClient;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.mitre.discovery.util.WebfingerURLNormalizer;
import org.mitre.openid.connect.client.model.IssuerServiceResponse;
import org.mitre.openid.connect.client.service.IssuerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Use Webfinger to discover the appropriate issuer for a user-given input string.
 * @author jricher
 *
 */
public class WebfingerIssuerService implements IssuerService {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(WebfingerIssuerService.class);

	// map of user input -> issuer, loaded dynamically from webfinger discover
	private LoadingCache<UriComponents, String> issuers;

	private Set<String> whitelist = new HashSet<>();
	private Set<String> blacklist = new HashSet<>();

	/**
	 * Name of the incoming parameter to check for discovery purposes.
	 */
	private String parameterName = "identifier";

	/**
	 * URL of the page to forward to if no identifier is given.
	 */
	private String loginPageUrl;

	/**
	 * Strict enfocement of "https"
	 */
	private boolean forceHttps = true;

	public WebfingerIssuerService() {
		issuers = CacheBuilder.newBuilder().build(new WebfingerIssuerFetcher());
	}

	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.client.service.IssuerService#getIssuer(javax.servlet.http.HttpServletRequest)
	 */
	@Override
	public IssuerServiceResponse getIssuer(HttpServletRequest request) {

		String identifier = request.getParameter(parameterName);
		if (!Strings.isNullOrEmpty(identifier)) {
			try {
				String issuer = issuers.get(WebfingerURLNormalizer.normalizeResource(identifier));
				if (!whitelist.isEmpty() && !whitelist.contains(issuer)) {
					throw new AuthenticationServiceException("Whitelist was nonempty, issuer was not in whitelist: " + issuer);
				}

				if (blacklist.contains(issuer)) {
					throw new AuthenticationServiceException("Issuer was in blacklist: " + issuer);
				}

				return new IssuerServiceResponse(issuer, identifier, null);
			} catch (UncheckedExecutionException | ExecutionException e) {
				logger.warn("Issue fetching issuer for user input: " + identifier + ": " + e.getMessage());
				return null;
			}

		} else {
			logger.warn("No user input given, directing to login page: " + loginPageUrl);
			return new IssuerServiceResponse(loginPageUrl);
		}
	}

	/**
	 * @return the parameterName
	 */
	public String getParameterName() {
		return parameterName;
	}

	/**
	 * @param parameterName the parameterName to set
	 */
	public void setParameterName(String parameterName) {
		this.parameterName = parameterName;
	}


	/**
	 * @return the loginPageUrl
	 */
	public String getLoginPageUrl() {
		return loginPageUrl;
	}

	/**
	 * @param loginPageUrl the loginPageUrl to set
	 */
	public void setLoginPageUrl(String loginPageUrl) {
		this.loginPageUrl = loginPageUrl;
	}

	/**
	 * @return the whitelist
	 */
	public Set<String> getWhitelist() {
		return whitelist;
	}

	/**
	 * @param whitelist the whitelist to set
	 */
	public void setWhitelist(Set<String> whitelist) {
		this.whitelist = whitelist;
	}

	/**
	 * @return the blacklist
	 */
	public Set<String> getBlacklist() {
		return blacklist;
	}

	/**
	 * @param blacklist the blacklist to set
	 */
	public void setBlacklist(Set<String> blacklist) {
		this.blacklist = blacklist;
	}

	/**
	 * @return the forceHttps
	 */
	public boolean isForceHttps() {
		return forceHttps;
	}

	/**
	 * @param forceHttps the forceHttps to set
	 */
	public void setForceHttps(boolean forceHttps) {
		this.forceHttps = forceHttps;
	}

	/**
	 * @author jricher
	 *
	 */
	private class WebfingerIssuerFetcher extends CacheLoader<UriComponents, String> {
		private HttpClient httpClient = HttpClientBuilder.create()
				.useSystemProperties()
				.build();
		private HttpComponentsClientHttpRequestFactory httpFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		private JsonParser parser = new JsonParser();

		@Override
		public String load(UriComponents key) throws Exception {

			RestTemplate restTemplate = new RestTemplate(httpFactory);
			// construct the URL to go to

			// preserving http scheme is strictly for demo system use only.
			String scheme = key.getScheme();

			if (!Strings.isNullOrEmpty(scheme) &&scheme.equals("http")) {
				if (forceHttps) {
					throw new IllegalArgumentException("Scheme must start with htps");
				} else {
					logger.warn("Webfinger endpoint MUST use the https URI scheme, overriding by configuration");
					scheme = "http://"; // add on colon and slashes.
				}
			} else {
				scheme = "https://";
			}

			// do a webfinger lookup
			URIBuilder builder = new URIBuilder(scheme
					+ key.getHost()
					+ (key.getPort() >= 0 ? ":" + key.getPort() : "")
					+ Strings.nullToEmpty(key.getPath())
					+ "/.well-known/webfinger"
					+ (Strings.isNullOrEmpty(key.getQuery()) ? "" : "?" + key.getQuery())
					);
			builder.addParameter("resource", key.toString());
			builder.addParameter("rel", "http://openid.net/specs/connect/1.0/issuer");

			try {

				// do the fetch
				logger.info("Loading: " + builder.toString());
				String webfingerResponse = restTemplate.getForObject(builder.build(), String.class);

				JsonElement json = parser.parse(webfingerResponse);

				if (json != null && json.isJsonObject()) {
					// find the issuer
					JsonArray links = json.getAsJsonObject().get("links").getAsJsonArray();
					for (JsonElement link : links) {
						if (link.isJsonObject()) {
							JsonObject linkObj = link.getAsJsonObject();
							if (linkObj.has("href")
									&& linkObj.has("rel")
									&& linkObj.get("rel").getAsString().equals("http://openid.net/specs/connect/1.0/issuer")) {

								// we found the issuer, return it
								return linkObj.get("href").getAsString();
							}
						}
					}
				}
			} catch (JsonParseException | RestClientException e) {
				logger.warn("Failure in fetching webfinger input", e.getMessage());
			}

			// we couldn't find it!

			if (key.getScheme().equals("http") || key.getScheme().equals("https")) {
				// if it looks like HTTP then punt: return the input, hope for the best
				logger.warn("Returning normalized input string as issuer, hoping for the best: " + key.toString());
				return key.toString();
			} else {
				// if it's not HTTP, give up
				logger.warn("Couldn't find issuer: " + key.toString());
				throw new IllegalArgumentException();
			}

		}

	}

}
