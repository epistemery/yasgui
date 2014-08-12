package com.data2semantics.yasgui.server.openid;

/*
 * #%L
 * YASGUI
 * %%
 * Copyright (C) 2013 Laurens Rietveld
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import com.data2semantics.yasgui.server.db.DbHelper;
import com.data2semantics.yasgui.server.fetchers.ConfigFetcher;
import com.data2semantics.yasgui.shared.StaticConfig;
import com.data2semantics.yasgui.shared.UserDetails;
import com.google.gwt.user.client.rpc.RemoteService;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import org.openid4java.OpenIDException;
import org.openid4java.consumer.ConsumerException;
import org.openid4java.consumer.ConsumerManager;
import org.openid4java.consumer.VerificationResult;
import org.openid4java.discovery.DiscoveryInformation;
import org.openid4java.discovery.Identifier;
import org.openid4java.message.AuthRequest;
import org.openid4java.message.AuthSuccess;
import org.openid4java.message.MessageExtension;
import org.openid4java.message.ParameterList;
import org.openid4java.message.ax.AxMessage;
import org.openid4java.message.ax.FetchRequest;
import org.openid4java.message.ax.FetchResponse;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.List;

/**
 * Created by IntelliJ IDEA. User: aviadbendov Date: Apr 18, 2008 Time: 9:27:03
 * PM
 * 
 * A servlet incharge of the OpenID authentication process, from authentication
 * to verification.
 */
public final class OpenIdServlet extends HttpServlet implements RemoteService {

	private static final long serialVersionUID = 6199183013097234155L;

	public static interface Callback {
		String getOpenIdServletURL(String baseUrl);

		String getLoginURL();

		String createUniqueIdForUser(JSONObject config, String loginString) throws ClassNotFoundException, FileNotFoundException, JSONException, SQLException, IOException;

		void saveIdentifierForUniqueId(File configDir, UserDetails userDetails) throws ClassNotFoundException, FileNotFoundException, JSONException, SQLException, IOException, ParseException;
	}

	private static Callback callback = new OpenIdCallback();

	public static final String authParameter = "app-openid-auth";
	public static final String appBaseUrl = "appBaseUrl";
	public static final String nameParameter = "app-openid-name";
	public static final String openIdCookieName = "app-openid-identifier";
	public static final String uniqueIdCookieName = "app-openid-uniqueid";

	private final ConsumerManager manager;

	public OpenIdServlet() throws ConsumerException {
		manager = new ConsumerManager();
	}

	/**
	 * <b>Note</b>: In a normal servlet environment, this method would probably
	 * redirect the response itself. However, since GWT servlets do not allow
	 * for such behavior, a path to this servlet is returned and the redirection
	 * is done on the client side.
	 * 
	 * @param openIdName
	 *            The OpenID identifier the user provided.
	 * @return The URL the browser should be redirected to.
	 */
	public static String getAuthenticationURL(String openIdName, String baseUrl, boolean inDebugMode) {
		// This is where a redirect for the response was supposed to occur;
		// however, since GWT doesn't allow that
		// on responses coming from a GWT servlet, only a redirect via the web
		// page is made.
		String url = MessageFormat.format("{3}?{1}=true&{2}={0}&{4}={5}", openIdName, authParameter, nameParameter,
				callback.getOpenIdServletURL(baseUrl), appBaseUrl, baseUrl);
		if (inDebugMode) {
			url += "&" + StaticConfig.DEBUG_ARGUMENT_KEY + "=" + StaticConfig.DEBUG_ARGUMENT_VALUE;

		}
		return url;
	}
	
	/**
	 * provide http basic authentication feature using authorization header; no
	 * password is checked here! we assume that the user is allowed to access
	 * this resources with the given username, that is, the password is checked
	 * elsewhere and the location to yasgui is protected so that only
	 * authenticated users are authorized to access it
	 * 
	 * this is sort of a hack because we're abusing the openIdCookieName and
	 * uniqueIdCookieName cookies to be able to track the user across a session; 
	 * both cookies are set to the given username
	 * 
	 * authentication is only happening if the basic auth username and the
	 * stored cookie value of openIdCookieName are different (this probably
	 * means that the user has "logged out"); also a user is logged out
	 * if the basic auth header is no longer present in the request
	 * 
	 * @param configDir
	 * @param userDetails
	 * @param request
	 * @param response
	 */
	public static void doBasicAuth(File configDir, UserDetails userDetails, HttpServletRequest request, HttpServletResponse response) {
		try {
			String authHeader = request.getHeader("Authorization");
			if (authHeader != null) {
				String encodedValue = authHeader.split(" ")[1];
				String decodedValue = new String(Base64.decodeBase64(encodedValue.getBytes()));
				String[] splitValue = decodedValue.split(":");
				
				String uniqueId = splitValue[0]; 
				String openId = uniqueId;
				
				if (HttpCookies.getCookieValue(request, openIdCookieName) != uniqueId) {
					DbHelper db = new DbHelper(configDir, request);
					
					HttpCookies.resetCookie(request, response, uniqueIdCookieName);
					HttpCookies.resetCookie(request, response, openIdCookieName);
					HttpCookies.setCookie(request, response, openIdCookieName, openId);
					HttpCookies.setCookie(request, response, uniqueIdCookieName, uniqueId);
					
					userDetails.setUniqueId(uniqueId);
					userDetails.setOpenId(openId);
					
					db.registerBasicAuth(userDetails, splitValue[0], splitValue[1]);
				}
			} else if (authHeader == null && HttpCookies.getCookieValue(request, openIdCookieName) != null) {
				// remove cookies because auth header is no longer present, log out;
				// we can't use the logOut method because it is not static and we are 
				// trying to avoid modifications to the existing code base as much 
				// as possible
				HttpCookies.resetCookie(request, response, openIdCookieName);
				HttpCookies.resetCookie(request, response, uniqueIdCookieName);
				userDetails.setUniqueId(null);
				userDetails.setOpenId(null);
			}
		} catch (Exception e) {
			System.out.println("error trying to authenticate through http basic auth header: ");
			System.out.println(e.toString());
		}
	}

	/**
	 * Returns the unique cookie and the OpenID identifier saved on the user's
	 * browser.
	 * 
	 * The servlet should be the only entity accessing and manipulating these
	 * cookies, so it is also in-charge of fetching them when needed.
	 * 
	 * @param request
	 *            The user's request to extract the cookies from.
	 * @param response
	 * @return Array containing { UniqueId, OpenID-Identifier }
	 * @throws IOException 
	 * @throws SQLException 
	 * @throws JSONException 
	 * @throws FileNotFoundException 
	 * @throws ClassNotFoundException 
	 * @throws ParseException 
	 */
	public static UserDetails getRequestUserInfo(File configDir, HttpServletRequest request, HttpServletResponse response) throws ClassNotFoundException, FileNotFoundException, JSONException, SQLException, IOException, ParseException {
		UserDetails userDetails = new UserDetails();
		userDetails.setOpenId(HttpCookies.getCookieValue(request, openIdCookieName));
		userDetails.setUniqueId(HttpCookies.getCookieValue(request, uniqueIdCookieName));
		
		// authenticate via basic auth if authorization header is present;
		// if not, we still provide the open id auth feature
		doBasicAuth(configDir, userDetails, request, response);
		
		if (userDetails.getOpenId() != null && userDetails.getUniqueId() != null) {
			//get more info
			DbHelper db = new DbHelper(configDir, request);
			userDetails = db.getUserDetails(userDetails);
		}
		
		return userDetails;
	}

	/**
	 * Implements the GET method by either sending it to an OpenID
	 * authentication or verification mechanism.
	 * 
	 * Checks the parameters of the GET method; if they contain the
	 * "authParameter" set to true, the authentication process is performed. If
	 * not, the verification process is performed (the parameters in the
	 * verification process are controlled by the OpenID provider).
	 * 
	 * @param request
	 *            The request sent to the servlet. Might come from the GWT
	 *            application or the OpenID provider.
	 * 
	 * @param response
	 *            The response sent to the user. Generally used to redirect the
	 *            user to the next step in the OpenID process.
	 * 
	 * @throws ServletException
	 *             Usually wrapping an OpenID process exception.
	 * @throws IOException
	 *             Usually when redirection could not be performed.
	 */
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		if (Boolean.valueOf(request.getParameter(authParameter))) {
			System.out.println("trying to authenticate");
			try {
				authenticate(request, response);
			} catch (Exception e) {
				e.printStackTrace();
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
			}
		}
		if (request.getParameter("logOut") != null) {
			System.out.println("logging out");
			logOut(request, response);
		} else {
			try {
				System.out.println("verify authenticatation");
				verify(request, response);
			} catch (Exception e) {
				e.printStackTrace();
//				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
			}
		}
	}

	private void logOut(HttpServletRequest request, HttpServletResponse response) throws IOException {
		System.out.println("logging out");
		String returnUrl = request.getParameter(appBaseUrl);
		if (request.getParameter(StaticConfig.DEBUG_ARGUMENT_KEY) != null) {
			returnUrl += "?" + StaticConfig.DEBUG_ARGUMENT_KEY + "="
					+ request.getParameter(StaticConfig.DEBUG_ARGUMENT_KEY);
		}
		System.out.println("resetting cookies");
		HttpCookies.resetCookie(request, response, openIdCookieName);
		HttpCookies.resetCookie(request, response, uniqueIdCookieName);
		System.out.println("sending redirect url");
		response.sendRedirect(returnUrl);

	}

	/**
	 * Discovers the OpenID provider from the provided user string, and starts
	 * an authentication process against it.
	 * 
	 * This is done in three steps:
	 * <ol>
	 * <li>Discover the OpenID provider URL</li>
	 * <li>Create a unique cookie and send it to the user, so that after the
	 * provider redirects the user back we'll know what to do with him.</li>
	 * <li>Redirect the user to the provider URL, supplying the verification URL
	 * as a return point.</li>
	 * </ol>
	 * 
	 * @param request
	 *            The request for the OpenID authentication.
	 * @param response
	 *            The response, used to redirect the user.
	 * 
	 * @throws IOException
	 *             Occurs when a redirection is not successful.
	 * @throws ServletException
	 *             Wrapping an OpenID exception.
	 * @throws JSONException 
	 * @throws ParseException 
	 * @throws SQLException 
	 * @throws ClassNotFoundException 
	 */
	private void authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException,
			ServletException, ParseException, JSONException, ClassNotFoundException, SQLException {
		final String loginString = request.getParameter(nameParameter);
		System.out.println("in authenticate");
		try {
			System.out.println("resetting cookies");
			HttpCookies.resetCookie(request, response, uniqueIdCookieName);
			HttpCookies.resetCookie(request, response, openIdCookieName);
			
			System.out.println("creating unique id");
			String uuid = callback.createUniqueIdForUser(ConfigFetcher.getJsonObjectFromPath(getServletContext().getRealPath("/")), loginString);
			HttpCookies.setCookie(request, response, uniqueIdCookieName, uuid);

			// perform discovery on the user-supplied identifier
			System.out.println("discovering");
			@SuppressWarnings("rawtypes")
			List discoveries = manager.discover(loginString);

			// attempt to associate with the OpenID provider
			// and retrieve one service endpoint for authentication
			System.out.println("retrieve single authentication endpoint");
			DiscoveryInformation discovered = manager.associate(discoveries);
			
			System.out.println("obtaining authrequest");
			// obtain a AuthRequest message to be sent to the OpenID provider
			String returnUrl = callback.getOpenIdServletURL(request.getParameter(appBaseUrl));
			if (request.getParameter(StaticConfig.DEBUG_ARGUMENT_KEY) != null) {
				returnUrl += "?" + StaticConfig.DEBUG_ARGUMENT_KEY + "="
						+ request.getParameter(StaticConfig.DEBUG_ARGUMENT_KEY);
			}
			System.out.println("authenticate cookies");
			AuthRequest authReq = manager.authenticate(discovered, returnUrl, null);
			System.out.println("fetch request");
			FetchRequest fetch = FetchRequest.createFetchRequest();
			System.out.println("fetching attributes");
			fetch.addAttribute("oiFirstName", "http://schema.openid.net/namePerson/first", true);
			fetch.addAttribute("oiLastName", "http://schema.openid.net/namePerson/last", true);
			fetch.addAttribute("aFirstName", "http://axschema.org/namePerson/first", true);
			fetch.addAttribute("aLastName", "http://axschema.org/namePerson/last", true);
			fetch.addAttribute("aFullName", "http://axschema.org/namePerson", true);
			fetch.addAttribute("aNickName", "http://axschema.org/namePerson/friendly", true);
			fetch.addAttribute("oiNickName", "http://openid.net/schema/namePerson/friendly", true);
			fetch.addAttribute("aEmail", "http://axschema.org/contact/email", true);
			fetch.addAttribute("oiEmail", "http://schema.openid.net/contact/email", true);
			
			System.out.println("adding extension");
			authReq.addExtension(fetch);
			// redirect to OpenID for authentication
			System.out.println("sending redirect");
			response.sendRedirect(authReq.getDestinationUrl(true));
		} catch (OpenIDException e) {
			System.out.println("oid exception!");
			e.printStackTrace();
			throw new ServletException("Login string probably caused an error. loginString = " + loginString, e);
		}
	}

	/**
	 * Checks the response received by the OpenID provider, and saves the user
	 * identifier for later use if the authentication was sucesssful.
	 * 
	 * <b>Note</b>: While confusing, the OpenID provider's response is in fact
	 * encapsulated within the request; this is because it is the provider who
	 * requested the page, and sent the response as parameters.
	 * 
	 * This is done in three steps:
	 * <ol>
	 * <li>Verify the OpenID resposne.</li>
	 * <li>If verification was successful, retrieve the OpenID identifier of the
	 * user and save it for later use.</li>
	 * <li>Redirect the user back to the main page of the application, together
	 * with a cookie containing his OpneID identifier.</li>
	 * </ol>
	 * 
	 * @param request
	 *            The request, containing the OpenID provider's response as
	 *            parameters.
	 * @param response
	 *            The response, used to redirect the user back to the
	 *            application page.
	 * @throws IOException 
	 * @throws JSONException 
	 * @throws ParseException 
	 * @throws FileNotFoundException 
	 * @throws ServletException 
	 * @throws SQLException 
	 * @throws ClassNotFoundException 
	 * @throws Exception 
	 */
	private void verify(HttpServletRequest request, HttpServletResponse response) throws FileNotFoundException, ParseException, JSONException, IOException, ServletException, ClassNotFoundException, SQLException {
		System.out.println("verifying");
		try {

			// extract the parameters from the authentication response
			// (which comes in as a HTTP request from the OpenID provider)
			ParameterList responseParams = new ParameterList(request.getParameterMap());
			System.out.println("extract receiving url");
			// extract the receiving URL from the HTTP request
			StringBuffer receivingURL = request.getRequestURL();
			String queryString = request.getQueryString();
			if (queryString != null && queryString.length() > 0)
				receivingURL.append("?").append(request.getQueryString());
			
			// verify the response; ConsumerManager needs to be the same
			// (static) instance used to place the authentication request
			System.out.println("actual verify");
			VerificationResult verification = manager.verify(receivingURL.toString(), responseParams, null);
			// examine the verification result and extract the verified
			// identifier
			System.out.println("getting verified id");
			Identifier id = verification.getVerifiedId();
			
			if (id != null) {
				System.out.println("id found");
				UserDetails userDetails = new UserDetails();
				userDetails.setUniqueId(HttpCookies.getCookieValue(request, uniqueIdCookieName));
				userDetails.setOpenId(id.getIdentifier());
				AuthSuccess authSuccess = (AuthSuccess) verification.getAuthResponse();
				System.out.println("retrieved auth response");
				if (authSuccess.hasExtension(AxMessage.OPENID_NS_AX)) {
					System.out.println("doing stuff for auth extension");
					MessageExtension ext = authSuccess.getExtension(AxMessage.OPENID_NS_AX);

					if (ext instanceof FetchResponse) {
						FetchResponse fetchResp = (FetchResponse) ext;
						System.out.println("adding attributes to user details");
						userDetails = addAttributes(fetchResp, userDetails);
					}

				}
				System.out.println("setting id cookie");
				HttpCookies.setCookie(request, response, openIdCookieName, id.getIdentifier());
				System.out.println("saving identifies for unique id");
				callback.saveIdentifierForUniqueId(new File(getServletContext().getRealPath("/")), userDetails);
			} else {
				System.out.println("id not found");
			}
			String url = callback.getLoginURL();
			if (request.getParameter(StaticConfig.DEBUG_ARGUMENT_KEY) != null) {
				url += "?" + StaticConfig.DEBUG_ARGUMENT_KEY + "="
						+ request.getParameter(StaticConfig.DEBUG_ARGUMENT_KEY);
			}
			System.out.println("sending redirect url");
			response.sendRedirect(url);
		} catch (OpenIDException e) {
			throw new ServletException("Could not verify identity", e);
		}
	}

	private UserDetails addAttributes(FetchResponse fetchResp, UserDetails userDetails) {
		if (fetchResp.getAttributeValue("aFirstName") != null) {
			userDetails.setFirstName(fetchResp.getAttributeValue("aFirstName"));
		} else {
			userDetails.setFirstName(fetchResp.getAttributeValue("oiFirstName"));
		}
		if (fetchResp.getAttributeValue("aLastName") != null) {
			userDetails.setLastName(fetchResp.getAttributeValue("aLastName"));
		} else {
			userDetails.setLastName(fetchResp.getAttributeValue("oiLastName"));
		}
		if (fetchResp.getAttributeValue("aEmail") != null) {
			userDetails.setEmail(fetchResp.getAttributeValue("aEmail"));
		} else {
			userDetails.setEmail(fetchResp.getAttributeValue("oiEmail"));
		}
		userDetails.setFullName(fetchResp.getAttributeValue("aFullName"));
		if (fetchResp.getAttributeValue("aNickName") != null) {
			userDetails.setNickName(fetchResp.getAttributeValue("aNickName"));
		} else {
			userDetails.setNickName(fetchResp.getAttributeValue("oiNickName"));
		}
		return userDetails;
	}

}