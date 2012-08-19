package com.data2semantics.yasgui.client;

import java.util.Date;
import java.util.HashMap;
import java.util.logging.Logger;

import com.data2semantics.yasgui.client.queryform.Helper;
import com.data2semantics.yasgui.client.queryform.ToolBar;
import com.data2semantics.yasgui.shared.Prefix;
import com.data2semantics.yasgui.shared.Settings;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.regexp.shared.MatchResult;
import com.google.gwt.regexp.shared.RegExp;
import com.google.gwt.user.client.Cookies;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.HTMLPane;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.Window;
import com.smartgwt.client.widgets.events.CloseClickEvent;
import com.smartgwt.client.widgets.events.CloseClickHandler;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.layout.VLayout;

public class View extends VLayout {
	private Logger logger = Logger.getLogger("");
	private YasguiServiceAsync remoteService = YasguiServiceAsync.Util.getInstance();
	public static String QUERY_INPUT_ID = "queryInput";
	private static String COOKIE_PREFIXES = "yasgui_prefixes";
	private static String COOKIE_SETTINGS = "yasgui_settings";
	
	private TextItem endpoint;
	private ToolBar toolBar;
	private VLayout queryResultContainer = new VLayout();
	private HashMap<String, Prefix> queryPrefixes = new HashMap<String, Prefix>();
	private Settings settings = new Settings();

	public View() {
		getSettingsFromCookie();
		setMargin(10);
		setWidth100();
		this.toolBar = new ToolBar(this);
		addMember(this.toolBar);
		// Img img = new Img("xml.png");
		// addMember(img);
		HTMLPane queryInput = new HTMLPane();
		queryInput.setHeight("350px");
		queryInput.setContents(getTextArea());
		addMember(queryInput);
		DynamicForm endpointForm = new DynamicForm();
		endpoint = new TextItem();
		endpoint.setTitle("Endpoint");
		endpoint.setWidth(250);
		endpoint.setDefaultValue(settings.getEndpoint());
		endpointForm.setFields(endpoint);
		addMember(endpointForm);
		addMember(queryResultContainer);
		setAutocompletePrefixes(false);
		
	}

	private String getTextArea() {
		String textArea = "" + "<textarea " + "id=\"" + QUERY_INPUT_ID + "\"" + ">" + settings.getQueryString() + "</textarea>";
		return textArea;

	}

	private ToolBar getToolBar() {
		return this.toolBar;
	}

	
	
	public void setAutocompletePrefixes(boolean forceUpdate) {
		String prefixesString = Cookies.getCookie(COOKIE_PREFIXES);
		if (forceUpdate || prefixesString == null) {
			//get prefixes from server
			getRemoteService().fetchPrefixes(forceUpdate,
					new AsyncCallback<String>() {
						public void onFailure(Throwable caught) {
							onError(caught.getMessage());
						}
						public void onSuccess(String prefixes) {
							Date expires = new Date();
							long nowLong = expires.getTime();
							nowLong = nowLong + (1000 * 60 * 60 * 24 * 1);//one day
							expires.setTime(nowLong);
							Cookies.removeCookie(COOKIE_PREFIXES);//appearently need to remove before setting it. Won't work otherwise
							Cookies.setCookie(COOKIE_PREFIXES, prefixes, expires);
							JsMethods.setAutocompletePrefixes(prefixes);
						}
					});
		} else {
			JsMethods.setAutocompletePrefixes(prefixesString);
		}
		
	}
	
	
	

	
	
	public void resetQueryResult() {
		Canvas[] members = queryResultContainer.getMembers();
		for (Canvas member: members) {
			queryResultContainer.removeMember(member);
		}
	}
	
	public void addQueryResult(Canvas member) {
		resetQueryResult();
		queryResultContainer.addMember(member);
	}
	
	public String getEndpoint() {
		return endpoint.getValueAsString();
	}
	
	
	public void storePrefixes() {
		String query = JsMethods.getQuery(QUERY_INPUT_ID);
		RegExp regExp = RegExp.compile("^\\s*PREFIX\\s*(\\w*):\\s*<(.*)>\\s*$", "gm");
		while (true) {
			MatchResult matcher = regExp.exec(query);
			if (matcher == null) break;
			queryPrefixes.put(matcher.getGroup(2), new Prefix(matcher.getGroup(1), matcher.getGroup(2)));
	    }
	}
	
	public HashMap<String, Prefix> getQueryPrefixes() {
		return this.queryPrefixes;
	}
	
	public void onError(String error) {
		onLoadingFinish();
		final Window window = new Window();
		window.setAutoSize(true);
		window.setTitle("Error");
		window.setShowMinimizeButton(false);
		window.setIsModal(true);
		window.setShowModalMask(true);
		window.setAutoCenter(true);
		window.addCloseClickHandler(new CloseClickHandler() {
			public void onCloseClick(CloseClickEvent event) {
				window.destroy();
			}
		});
		Label label = new Label(error);
		window.addItem(label);
		window.draw();
	}

	public void onError(Throwable throwable) {
		String st = throwable.getClass().getName() + ": " + throwable.getMessage();
		for (StackTraceElement ste : throwable.getStackTrace()) {
			st += "\n" + ste.toString();
		}
		onError(st);
	}

	public void onLoadingFinish() {
		// loading.loadingEnd();
	}

	public YasguiServiceAsync getRemoteService() {
		return remoteService;
	}
	
	public Logger getLogger() {
		return this.logger;
	}
	
	public Settings getSettings() {
		return this.settings;
	}
	
	public void storeSettingsInCookie() {
		updateSettings();
		Cookies.removeCookie(COOKIE_SETTINGS);
		Cookies.setCookie(COOKIE_SETTINGS, Helper.getHashMapAsJson(settings.getSettingsHashMap()));
	}
	
	public void getSettingsFromCookie() {
		String jsonString = Cookies.getCookie(COOKIE_SETTINGS);
		if (jsonString != null && jsonString.length() > 0) {
			settings = Helper.getSettingsFromJsonString(jsonString);
		} 
	}
	public void updateSettings() {
		settings = new Settings();
		settings.setQueryString(JsMethods.getQuery(QUERY_INPUT_ID));
		settings.setEndpoint(getEndpoint());
		settings.setOutputFormat(getToolBar().getSelectedOutput());
	}
}
