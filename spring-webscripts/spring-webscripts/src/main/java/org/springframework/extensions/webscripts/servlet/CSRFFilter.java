/*
 * Copyright (C) 2005-2015 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.springframework.extensions.webscripts.servlet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.extensions.config.Config;
import org.springframework.extensions.config.ConfigElement;
import org.springframework.extensions.config.ConfigService;
import org.springframework.extensions.surf.util.Base64;
import org.springframework.extensions.surf.util.URLDecoder;
import org.springframework.extensions.surf.util.URLEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CSRF Filter class for the web-tier checking that certain requests supply a secret token that is compared
 * to the token existing in the user's session to mitigate CSRF attacks. It is also possible to check the referer or
 * origin headers.
 * <p>
 * The logic is configurable making it possible to: disable the filter, use 1 and same token per session, refresh the
 * token when certain urls are requested (i.e. on a new page visit, which is recommended) OR refresh the token on
 * every request made to the server (which is not recommended since multiple requests might span over each other making
 * some tokens stale and therefor get treated as a CSRF attack).
 * <p>
 * It is recommended to run the filter with a filter-mapping that NOT includes client side resources since that
 * is pointless and unnecessarily would decrease the performance of the webapp (even though the filter still would work).
 *
 * @author Erik Winlof
 * @since 6.11
 * NOTE: Copied from Slingshot project to WebScripts 6.11 on 5/11/2016
 */
public class CSRFFilter implements Filter
{
    private static Log logger = LogFactory.getLog(CSRFFilter.class);
    
    private ServletContext servletContext = null;
    
    private boolean enabled = true;
    private boolean createSession = false;
    private List<Rule> rules = null;
    private Map<String, String> properties = new HashMap<String, String>();
    private String PROPERTY_PREFIX = "csrf.filter.";
    private Boolean HTTP_SECURED_SESSION_PROP = null;
    private String COOKIES_SAMESITE = null;
    private String PARAM_ENABLED = "enabled";
    private final String PARAM_TOKEN = "token";

    private final String USER_ID = "_alf_USER_ID";
    private final String ISSET_USER_ID = "isset_alf_USER_ID";
    private final String ISSET_TOKEN = "isset_token";

    // Global properties
    private Properties globalProperties = null;

    /**
     * Parses the filter rule config.
     *
     * @param config The filter config
     * @throws ServletException if the rule filter config is invalid
     */
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        HTTP_SECURED_SESSION_PROP = Boolean.parseBoolean(System.getProperty("http.secured.session"));
        COOKIES_SAMESITE = System.getProperty("cookies.sameSite");

        servletContext = config.getServletContext();
        
        ApplicationContext context = getApplicationContext();

        // Override the xml by alfresco-global.properties
        try
        {
            globalProperties = (Properties) getApplicationContext().getBean("global-properties");
        }
        catch (NoSuchBeanDefinitionException exc)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("global-properties bean is missing" + exc);
            }
        }

        if (globalProperties != null)
        {
            for (Map.Entry<Object, Object> globalEntry : globalProperties.entrySet())
            {
                String property = (String) globalEntry.getKey();
                if (property.startsWith(PROPERTY_PREFIX))
                {
                    if (property.replaceFirst(PROPERTY_PREFIX, "").equals(PARAM_ENABLED))
                    {
                        enabled = Boolean.parseBoolean((String) globalEntry.getValue());
                    }
                }
            }
        }

        ConfigService configService = (ConfigService)context.getBean("web.config");

        // Retrieve the remote configuration
        Config csrfConfig = (Config) configService.getConfig("CSRFPolicy");
        if (csrfConfig == null || !enabled)
        {
            enabled = false;
            if (logger.isDebugEnabled())
                logger.debug("There is no 'CSRFPolicy' config, filter will allow all requests.");
        }
        else
        {
            // Parse Session flag
            ConfigElement sessionConfig = csrfConfig.getConfigElement("session");
            createSession = (sessionConfig != null && "true".equals(sessionConfig.getValue()));
            if (createSession && logger.isDebugEnabled())
                logger.debug("The CSRFFilter will automatically create a user Session on a path match.");
            
            // Parse properties
            ConfigElement propertiesConfig = csrfConfig.getConfigElement("properties");
            if (propertiesConfig != null)
            {
                List<ConfigElement> propertiesConfigList = propertiesConfig.getChildren();
                String value;
                if (propertiesConfigList != null && propertiesConfigList.size() > 0)
                {
                    for (ConfigElement propertyConfig : propertiesConfigList)
                    {
                        value = propertyConfig.getValue();
                        properties.put(propertyConfig.getName(), value != null ? value : "");
                    }
                }
            }

            // Parse filter and its rules
            ConfigElement filterConfig = csrfConfig.getConfigElement("filter");
            if (filterConfig == null)
            {
                enabled = false;
                if (logger.isDebugEnabled())
                    logger.debug("The 'CSRFPolicy' config had no filter, filter will allow all requests.");
            }
            else
            {
                List<ConfigElement> rulesConfigList = filterConfig.getChildren("rule");
                if (rulesConfigList == null || rulesConfigList.size() == 0)
                {
                    enabled = false;
                    if (logger.isDebugEnabled())
                        logger.debug("The 'CSRFPolicy' filter config was empty, filter will allow all requests.");
                }
                else
                {
                    rules = new LinkedList<Rule>();
                    for (ConfigElement ruleConfig : rulesConfigList)
                    {
                        rules.add(createRule(ruleConfig));
                    }
                }
            }
        }
    }

    /**
     * Creates a rule object based on the config.
     *
     * @param ruleConfig The rule config element
     * @return A rul eobject created form the config
     * @throws ServletException if the config is invalid
     */
    protected Rule createRule(final ConfigElement ruleConfig) throws ServletException
    {
        final Rule rule = new Rule();
        
        // Request
        ConfigElement requestConfig = ruleConfig.getChild("request");
        if (requestConfig != null)
        {
            // Method
            rule.setMethod(resolve(requestConfig.getChildValue("method")));
            
            // Path
            rule.setPath(resolve(requestConfig.getChildValue("path")));
            
            // Headers
            List<ConfigElement> headerConfigs = requestConfig.getChildren("header");
            if (headerConfigs != null && headerConfigs.size() > 0)
            {
                Map<String, String> headers = new HashMap<String, String>();
                String value;
                for (ConfigElement headerConfig : headerConfigs)
                {
                    value = headerConfig.getValue();
                    headers.put(resolve(headerConfig.getAttribute("name")), resolve(value));
                }
                rule.setHeaders(headers);
            }
            
            // Session
            ConfigElement sessionConfig = requestConfig.getChild("session");
            if (sessionConfig != null)
            {
                // Session attributes
                List<ConfigElement> attributeConfigs = sessionConfig.getChildren("attribute");
                if (attributeConfigs != null && attributeConfigs.size() > 0)
                {
                    Map<String, String> sessionAttributes = new HashMap<String, String>();
                    String value;
                    for (ConfigElement attributeConfig : attributeConfigs)
                    {
                        value = attributeConfig.getValue();
                        sessionAttributes.put(resolve(attributeConfig.getAttribute("name")), resolve(value));
                    }
                    rule.setSessionAttributes(sessionAttributes);
                }
            }
        }
        
        // Actions
        List<ConfigElement> actionConfigs = ruleConfig.getChildren("action");
        if (actionConfigs != null && actionConfigs.size() > 0)
        {
            List<Action> actions = new LinkedList<Action>();
            String actionName;
            Action action;
            Map<String, String> parameters;
            List<ConfigElement> actionParameterConfigs;
            for (ConfigElement actionConfig : actionConfigs)
            {
                actionName = resolve(actionConfig.getAttribute("name"));
                action = createAction(actionName);
                if (action == null)
                {
                    String message = "There is no action named '" + actionName + "'";
                    if (logger.isErrorEnabled())
                        logger.error(message);
                    throw new ServletException(message);
                }
                action.setName(actionName);
                parameters = new HashMap<String, String>();
                
                // Action parameters
                actionParameterConfigs = actionConfig.getChildren("param");
                if (actionParameterConfigs != null)
                {
                    for (ConfigElement actionParameterConfig : actionParameterConfigs)
                    {
                        parameters.put(resolve(actionParameterConfig.getAttribute("name")), resolve(actionParameterConfig.getValue()));
                    }
                }
                action.init(parameters);
                actions.add(action);
            }
            rule.setActions(actions);
        }
        return rule;
    }

    /**
     * Creates a rule action based on a name
     *
     * @param name The name of the action, can be "generateToken", "assertToken" and "clearToken"
     * @return An action object
     * @throws ServletException if there is no action for name
     */
    protected Action createAction(String name) throws ServletException
    {
        switch (name)
        {
            case "generateToken":
                return new GenerateTokenAction();
            case "assertToken":
                return new AssertTokenAction();
            case "clearToken":
                return new ClearTokenAction();
            case "assertReferer":
                return new AssertRefererAction();
            case "assertOrigin":
                return new AssertOriginAction();
            case "throwError":
                return new ThrowErrorAction();
            default:
                return null;
        }
    }

    /**
     * Will check the requests method, path, request headers & the session's attributes against the rule config
     * to see which rule actions that should be used, will either generate a new token, assert that the request's token
     * equals the session's token, remove the token fmor the cookie and session OR simply do nothing.
     *
     * @param servletRequest The servlet request
     * @param servletResponse The servlet response
     * @param filterChain The filter chain
     * @throws IOException
     * @throws ServletException if the request requires a CSRF token but there is no such token in the request matching
     * the token in the user's session.
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException
    {
        if (enabled && servletRequest instanceof HttpServletRequest && servletResponse instanceof HttpServletResponse)
        {
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            HttpSession session = request.getSession(createSession);

            this.preprocessOnHttpBasicAuth(request, session);

            for (Rule rule : rules)
            {
                if (matchRequest(rule, request, session))
                {
                    List<Action> actions = rule.getActions();
                    if (actions != null)
                    {
                        for (Action action : actions)
                        {
                            action.run(request, response, session);
                        }
                    }
                    this.postprocessOnHttpBasicAuth(request, session);
                    filterChain.doFilter(servletRequest, servletResponse);
                    return;
                }
            }

            this.postprocessOnHttpBasicAuth(request, session);
        }
        
        // Proceed as usual
        filterChain.doFilter(servletRequest, servletResponse);
    }

    /**
     * If the request is using HTTP Basic Authentication
     * then we set the _alf_USER_ID and Alfresco-CSRFToken into session, so the CSRF rules will be applied to the request
     *
     * @param request The servlet request
     * @param session The session
     */
    protected void preprocessOnHttpBasicAuth(HttpServletRequest request, HttpSession session)
    {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null)
        {
            // Get the name used for CSRF token from configuration
            String tokenName = properties.getOrDefault(PARAM_TOKEN, "Alfresco-CSRFToken");

            // Set the _alf_USER_ID only if the attribute doesn't already exist in session
            if (session.getAttribute(USER_ID) == null)
            {
                String[] authParts = authHeader.split(" ");
                if (authParts.length == 2 && authParts[0].equalsIgnoreCase("basic"))
                {
                    String decodedAuthHeader = new String(Base64.decode(authParts[1]));
                    String[] decodedAuthParts = decodedAuthHeader.split(":");
                    if (decodedAuthParts.length == 2)
                    {
                        session.setAttribute(USER_ID, decodedAuthParts[0]);
                        request.setAttribute(ISSET_USER_ID, true);
                    }
                }
            }

            // Set the CSRF token only if the attribute doesn't already exists in the session
            if (session.getAttribute(tokenName) == null)
            {
                Cookie[] cookies = request.getCookies();
                if (cookies != null)
                {
                    for (Cookie cookie: cookies)
                    {
                        if (cookie.getName().equals(tokenName))
                        {
                            session.setAttribute(tokenName, new ArrayList<>(List.of(URLDecoder.decode(cookie.getValue()))));
                            request.setAttribute(ISSET_TOKEN, true);
                        }
                    }
                }
            }

            // Avoid to apply rules that don't validate the CSRF token, when we're doing a POST, PUT or DELETE
            // by setting an invalid CSRF token
            // This forces to check for the CSRF token validity, when the request has the token header but no CSRF token within the cookie
            if (session.getAttribute(tokenName) == null &&
                (request.getMethod().equals(HttpPost.METHOD_NAME) ||
                    request.getMethod().equals(HttpPut.METHOD_NAME) ||
                    request.getMethod().equals(HttpDelete.METHOD_NAME)
                )
            )
            {
                session.setAttribute(tokenName, new ArrayList<>(List.of("")));
                request.setAttribute(ISSET_TOKEN, true);
            }
        }
    }

    /**
     * If the request is using HTTP Basic Authentication
     * then clean the session attributes set in preprocessOnHttpBasicAuth
     * @param request
     * @param session
     */
    protected void postprocessOnHttpBasicAuth(HttpServletRequest request, HttpSession session)
    {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null)
        {
            // Get the name used for CSRF token from configuration
            String tokenName = properties.getOrDefault(PARAM_TOKEN, "Alfresco-CSRFToken");

            if (request.getAttribute(ISSET_USER_ID) != null)
            {
                session.removeAttribute(USER_ID);
            }

            if (request.getAttribute(ISSET_TOKEN) != null)
            {
                session.removeAttribute(tokenName);
            }
        }
    }

    @Override
    public void destroy()
    {
    }

    /**
     * Returns the path for a request where a path is the request uri with the request context stripped out.
     *
     * @param request The http request
     * @return The path for a request where a path is the request uri with the request context stripped out.
     */
    protected String getPath(HttpServletRequest request)
    {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * Compare the request against the configured rules.
     *
     * @param rule The rule to match against the request and session
     * @param request The http request
     * @param session The user's session
     * @return The first rule that matches the request and sessions or null if there is no such rule.
     * @throws ServletException
     */
    protected boolean matchRequest(Rule rule, HttpServletRequest request, HttpSession session) throws ServletException
    {
        // Match method
        if (rule.getMethod() != null && !matchString(request.getMethod(), rule.getMethod()))
        {
            return false;
        }
        
        // Match path
        if (rule.getPath() != null && !matchString(getPath(request), rule.getPath()))
        {
            return false;
        }
        
        // Match headers (if specified)
        Map<String, String> headers = rule.getHeaders();
        if (headers != null)
        {
            for (final String headerName: headers.keySet())
            {
                if (!matchString(request.getHeader(headerName), headers.get(headerName)))
                {
                    return false;
                }
            }
        }
        
        // Match session attributes (if specified)
        boolean matched = true;
        Map<String, String> sessionAttributes = rule.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.size() != 0)
        {
            if (session == null)
            {
                matched = false;
            }
            else
            {
                for (final String name: sessionAttributes.keySet())
                {
                    Object value = session.getAttribute(name);

                    // If the session attribute is a list of strings (i.e. tokens) lets make sure to check against the last position
                    if (value instanceof List)
                    {
                        List list = (List) value;
                        value = list.get(list.size() - 1);
                    }

                    if (value != null && !(value instanceof String))
                    {
                        // We can't match a non string and non null value with a string, so return false
                        matched = false;
                        break;
                    }

                    if (!matchString((String) value, sessionAttributes.get(name)))
                    {
                        matched = false;
                        break;
                    }
                }
            }
        }
        return matched;
    }

    /**
     * Checks if str matches the regular expression defined in regexp.
     *
     * @param str The value to match
     * @param regexp The regular expression to match against str
     * @return true if str matches regexp
     */
    protected boolean matchString(String str, String regexp)
    {
        if (regexp == null && str == null)
        {
            return true;
        }
        
        if ((regexp != null && str == null) || (regexp == null && str != null))
        {
            return false;
        }
        
        // There was a condition and a value, lets see if they match
        return str.matches(regexp);
    }

    /**
     * Retrieves the root application context
     *
     * @return application context
     */
    private ApplicationContext getApplicationContext()
    {
        return WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
    }

    private String listToString(List<String> list)
    {
        String str = "";
        for (int i = 0, il = list.size(); i < il; i++)
        {
            if (i != 0)
            {
                str += ", ";
            }
            str += list.get(i);
        }
        return str;
    }

    /**
     * Internal representation of a rule.
     */
    private class Rule
    {
        protected String method;
        protected String path;
        protected Map<String, String> headers;
        protected Map<String, String> sessionAttributes;
        protected List<Action> actions;
        
        public String getMethod()
        {
            return method;
        }
        
        public void setMethod(String method)
        {
            this.method = method;
        }
        
        public String getPath()
        {
            return path;
        }
        
        public void setPath(String path)
        {
            this.path = path;
        }
        
        public Map<String, String> getHeaders()
        {
            return headers;
        }
        
        public void setHeaders(Map<String, String> headers)
        {
            this.headers = headers;
        }
        
        public Map<String, String> getSessionAttributes()
        {
            return sessionAttributes;
        }
        
        public void setSessionAttributes(Map<String, String> sessionAttributes)
        {
            this.sessionAttributes = sessionAttributes;
        }
        
        public List<Action> getActions()
        {
            return actions;
        }
        
        public void setActions(List<Action> actions)
        {
            this.actions = actions;
        }
    }

    /**
     * Returns the current server's scheme, name & port
     *
     * @param request The http request
     * @return the current server's scheme, name & port
     */
    private String getServerString(HttpServletRequest request)
    {
        final String scheme = request.getScheme();
        final int port = request.getServerPort();
        String currentServerContext;
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))
        {
            currentServerContext = scheme + "://" + request.getServerName();
        }
        else
        {
            currentServerContext = scheme + "://" + request.getServerName() + ':' + port;
        }
        return currentServerContext;
    }

    private String resolve(String str)
    {
        return resolve(str, properties);
    }

    private String resolve(String str, Map<String, String> propertyMap)
    {
        if (str == null)
        {
            return null;
        }
        Pattern pattern = Pattern.compile("\\{(.+?)\\}");
        Matcher matcher = pattern.matcher(str);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find())
        {
            if (propertyMap.containsKey(matcher.group(1)))
            {
                String replacement = resolve(propertyMap.get(matcher.group(1)), propertyMap);
                matcher.appendReplacement(buffer, replacement != null ? Matcher.quoteReplacement(replacement) : "null");
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Abstract base class representing a rule action.
     */
    private abstract class Action
    {
        protected String name;
        protected Map<String, String> params = new HashMap<String, String>();

        public void setName(String name)
        {
            this.name = name;
        }
        public void init(Map<String, String> params) throws ServletException
        {
            this.params = params;
        }
        
        public abstract void run(HttpServletRequest request, HttpServletResponse response, HttpSession session) throws ServletException;
    }

    /**
     * Action that will generate a token in the session and set it in the cookie.
     */
    private class GenerateTokenAction extends Action
    {
        public static final String PARAM_SESSION = "session";
        public static final String PARAM_COOKIE = "cookie";
        public static final String PARAM_SIZE = "size";
        public static final String PARAM_DELAY = "delay";

        private final String SESSION_ATTRIBUTE_TOKEN_REFRESHED = this.getClass().getName() + ".SESSION_ATTRIBUTE_TOKEN_REFRESHED";

        private final SecureRandom random = new SecureRandom();

        private String session = null;
        private String cookie = null;
        private int size = 5;
        private long delay = 3000;

        /**
         * Requires the following params; the name of the cookie to set the token in and the name of the session
         * attribute to place the token in. Defined in params with key "cookie" and "session".
         *
         * @param params Action paremeters
         * @throws ServletException
         */
        public void init(Map<String, String> params) throws ServletException
        {
            super.init(params);

            if (params != null)
            {
                if (params.containsKey(PARAM_SESSION))
                {
                    session = params.get(PARAM_SESSION);
                }
                if (params.containsKey(PARAM_COOKIE))
                {
                    cookie = params.get(PARAM_COOKIE);
                }
                if (params.containsKey(PARAM_SIZE))
                {
                    String sizeParam = params.get(PARAM_SIZE);
                    try
                    {
                        size = Integer.parseInt(sizeParam);
                    }
                    catch(NumberFormatException nfe)
                    {
                        String message = "Parameter '" + PARAM_SIZE + "' must be an integer with a value greater or equals to 1.";
                        if (logger.isErrorEnabled())
                            logger.error(message);
                        throw new ServletException(message);
                    }
                    if (size < 1)
                    {
                        String message = "Parameter '" + PARAM_SIZE + "' must be an integer with a value greater than or equal to 1.";
                        if (logger.isErrorEnabled())
                            logger.error(message);
                        throw new ServletException(message);
                    }
                }
                if (params.containsKey(PARAM_DELAY))
                {
                    String delayParam = params.get(PARAM_DELAY);
                    try
                    {
                        delay = Integer.parseInt(delayParam);
                    }
                    catch(NumberFormatException nfe)
                    {
                        String message = "Parameter '" + PARAM_DELAY + "' must be an integer or long.";
                        if (logger.isErrorEnabled())
                            logger.error(message);
                        throw new ServletException(message);
                    }
                }
            }
            
            // Check for mandatory parameters
            if (session == null || cookie == null)
            {
                String message = "Parameter '" + PARAM_SESSION + "' and '" + PARAM_COOKIE + "' must be defined.";
                if (logger.isErrorEnabled())
                    logger.error(message);
                throw new ServletException(message);
            }
        }
        
        public void run(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpSession httpSession)
        {
            final byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            final String newToken = Base64.encodeBytes(bytes);
            
            // Set in session
            if (httpSession != null)
            {
                List<String> sessionTokens = (List<String>) httpSession.getAttribute(session);
                if (sessionTokens == null)
                {
                    // There was no previous token, lets add it inside the array
                    sessionTokens = Collections.synchronizedList(new LinkedList<String>());
                    sessionTokens.add(newToken);
                    httpSession.setAttribute(session, sessionTokens);
                    httpSession.setAttribute(SESSION_ATTRIBUTE_TOKEN_REFRESHED, new Date().getTime());
                }
                else if (httpSession.getAttribute(SESSION_ATTRIBUTE_TOKEN_REFRESHED) == null)
                {
                    httpSession.setAttribute(SESSION_ATTRIBUTE_TOKEN_REFRESHED, new Date().getTime());
                }
                else
                {
                    Long now = new Date().getTime();
                    Long tokenRefreshed = (Long) httpSession.getAttribute(SESSION_ATTRIBUTE_TOKEN_REFRESHED);
                    if (now > (tokenRefreshed + delay))
                    {
                        // Its been long enough since we set the old token, lets add a new token
                        sessionTokens.add(newToken);
                        httpSession.setAttribute(SESSION_ATTRIBUTE_TOKEN_REFRESHED, new Date().getTime());

                        if (logger.isDebugEnabled())
                            logger.debug("Generate token " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + " :: '" + newToken + "'");
                    }
                    else
                    {
                        // Return since we don't need to trim the queue and do NOT want to set the cookie
                        return;
                    }

                    // Check if the queue has become to big, if so lets trim it
                    if (sessionTokens.size() > size)
                    {
                        // The submitted token was at the last position of allowed tokens, that means the client now have
                        // received the newest token and that the previous tokens can be removed.
                        sessionTokens.subList(0, sessionTokens.size() - size).clear();
                    }
                }
            }

            // Expose the new token as a cookie to the client
            int TIMEOUT = 60*60*24*7;
            String userCookie = cookie + "=" + URLEncoder.encode(newToken) + ";";
            if (httpServletRequest.getContextPath().isEmpty())
            {
                userCookie += " Path=/;";
            }
            else
            {
                userCookie += " Path=" + httpServletRequest.getContextPath() + ";";
            }
            userCookie += " Max-Age=" + TIMEOUT + ";";
            if (HTTP_SECURED_SESSION_PROP)
            {
                userCookie += " Secure;";
            }
            if (COOKIES_SAMESITE != null)
            {
                userCookie += " SameSite="+COOKIES_SAMESITE+";";
            }
            httpServletResponse.addHeader(HttpHeaders.SET_COOKIE,userCookie);
        }
    }

    /**
     * An action that asserts the request contains the token (either in the requets header or as a url parameter) and
     * that the token has the same value as the token in the user's session.
     */
    private class AssertTokenAction extends Action
    {
        public static final String PARAM_SESSION = "session";
        public static final String PARAM_HEADER = "header";
        public static final String PARAM_PARAMETER = "parameter";

        private String session = null;
        private String header = null;
        private String parameter = null;

        /**
         * Requires the following params; the name of the request header to look for the token in, the name of the url
         * parameter to look for the token and the name of the session attribute that holds the user's session.
         * Defined in params with key "header", "parameter" and "session".
         *
         * @param params The action parameters
         * @throws ServletException
         */
        public void init(Map<String, String> params) throws ServletException
        {
            super.init(params);

            if (params != null)
            {
                if (params.containsKey(PARAM_SESSION))
                {
                    session = params.get(PARAM_SESSION);
                }
                if (params.containsKey(PARAM_HEADER))
                {
                    header = params.get(PARAM_HEADER);
                }
                if (params.containsKey(PARAM_PARAMETER))
                {
                    parameter = params.get(PARAM_PARAMETER);
                }
            }

            // Check for mandatory parameters
            if (session == null)
            {
                String message = "Parameter '" + PARAM_SESSION + "' must be defined.";
                if (logger.isErrorEnabled())
                    logger.error(message);
                throw new ServletException(message);
            }
            if (header == null && parameter == null)
            {
                String message = "Parameter '" + PARAM_HEADER + "' or '" + PARAM_PARAMETER + "' must be defined.";
                if (logger.isErrorEnabled())
                    logger.error(message);
                throw new ServletException(message);
            }
        }
        
        public void run(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpSession httpSession) throws ServletException
        {
            List<String> sessionTokens = null;
            if (httpSession != null)
            {
                sessionTokens = (List<String>) httpSession.getAttribute(session);
            }
            if (header != null)
            {
                String headerToken = httpServletRequest.getHeader(header);
                
                if (logger.isDebugEnabled())
                    logger.debug("Assert token " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + " :: session: '"
                            + listToString(sessionTokens) + "' vs header: '" + headerToken + "'");
                
                if (headerToken == null || sessionTokens == null || !sessionTokens.contains(headerToken))
                {
                    String message = "Possible CSRF attack noted when comparing token in session and request header. Request: "
                            + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI();
                    if (logger.isInfoEnabled())
                        logger.info(message);

                    throw new ServletException(message);
                }
            }
            else if (parameter != null)
            {
                String parameterToken = httpServletRequest.getParameter(parameter);
                
                if (logger.isDebugEnabled())
                    logger.debug("Assert token " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + " :: session: '"
                            + listToString(sessionTokens) + "' vs parameter: '" + parameterToken + "'");
                
                if (parameterToken == null || sessionTokens == null || !sessionTokens.contains(parameterToken))
                {
                    String message = "Possible CSRF attack noted when comparing token in session and request parameter. Request: "
                            + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI();
                    if (logger.isInfoEnabled())
                        logger.info(message);

                    throw new ServletException(message);
                }
            }
        }
    }

    /**
     * Action that clears the token from the user's session and the also clears the token set in the cookie from the browser.
     */
    private class ClearTokenAction extends Action
    {
        public static final String PARAM_SESSION = "session";
        public static final String PARAM_COOKIE = "cookie";

        private String cookie = null;
        private String session = null;

        /**
         * Requires the following params; the name of the cookie to clear the value of and the name of the session
         * attribute to clear the value of . Defined in params with key "cookie" and "session".
         *
         * @param params
         * @throws ServletException
         */
        public void init(Map<String, String> params) throws ServletException
        {
            super.init(params);

            if (params != null)
            {
                if (params.containsKey(PARAM_SESSION))
                {
                    session = params.get(PARAM_SESSION);
                }
                if (params.containsKey(PARAM_COOKIE))
                {
                    cookie = params.get(PARAM_COOKIE);
                }
            }

            // Check for mandatory parameters
            if (session == null || cookie == null)
            {
                String message = "Parameter '" + PARAM_SESSION + "' and '" + PARAM_COOKIE + "' must be defined.";
                if (logger.isErrorEnabled())
                    logger.error(message);
                throw new ServletException(message);
            }
        }
        
        public void run(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpSession httpSession)
        {
            if (logger.isDebugEnabled())
                logger.debug("Clear token " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI());
            
            // Remove token from session
            if (httpSession != null)
            {
                httpSession.setAttribute(session, null);
            }
            
            // Clear token cookie from the client
            String userCookie = cookie + "=;";
            userCookie += " Path=" + httpServletRequest.getContextPath() + ";";
            userCookie += " Max-Age=0;";
            if (HTTP_SECURED_SESSION_PROP)
            {
                userCookie += " Secure;";
            }
            if (COOKIES_SAMESITE != null)
            {
                userCookie += " SameSite=" + COOKIES_SAMESITE + ";";
            }
            httpServletResponse.addHeader(HttpHeaders.SET_COOKIE,userCookie);
        }
    }

    /**
     * An action that asserts the request's 'Referer' header matches the current server name or the "referer" param.
     *
     * Note that the word “referrer” is misspelled in the RFC as well as in most implementations.
     */
    private class AssertRefererAction extends Action
    {
        public static final String PARAM_ALWAYS = "always";
        public static final String PARAM_REFERER = "referer";
        public static final String HEADER_REFERER = "Referer";

        private boolean always = false;
        private String referer = null;

        /**
         * Requires the following params; a boolean deciding if the referer header MUST be present when validated.
         * Defined in a param with key "always".
         *
         * @param params The action parameters
         * @throws ServletException
         */
        public void init(Map<String, String> params) throws ServletException
        {
            super.init(params);

            // Check for mandatory parameters
            if (params != null)
            {
                if (params.containsKey(PARAM_ALWAYS))
                {
                    String alwaysParam = params.get(PARAM_ALWAYS);
                    if (!alwaysParam.equals("true") && !alwaysParam.equals("false"))
                    {
                        String message = "Parameter '" + PARAM_ALWAYS + "' must be a boolean and be set to true or false.";
                        if (logger.isErrorEnabled())
                            logger.error(message);
                        throw new ServletException(message);
                    }
                    always = alwaysParam.equals("true");
                }
                if (params.containsKey(PARAM_REFERER))
                {
                    referer = params.get(PARAM_REFERER);
                }
            }

            // Override the xml by alfresco-global.properties
            if (globalProperties != null)
            {
                for (Map.Entry<Object, Object> globalEntry : globalProperties.entrySet())
                {
                    String property = (String) globalEntry.getKey();
                    if (property.startsWith(PROPERTY_PREFIX))
                    {
                        if (property.replaceFirst(PROPERTY_PREFIX, "").equals(PARAM_REFERER))
                        {
                            referer = (String) globalEntry.getValue();
                        }
                        else if (property.replaceFirst(PROPERTY_PREFIX, "")
                                .equals(PARAM_REFERER + "." + PARAM_ALWAYS))
                        {
                            always = Boolean.valueOf((String) globalEntry.getValue());
                        }
                    }
                }
            }
        }

        public void run(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpSession httpSession) throws ServletException
        {
            String refererHeader = httpServletRequest.getHeader(HEADER_REFERER);
            if (refererHeader == null)
            {
                refererHeader = "";
            }

            String currentServer = getServerString(httpServletRequest);
            String refererServer = params.containsKey(PARAM_REFERER) ? params.get(PARAM_REFERER) : null;

            if (logger.isDebugEnabled())
                logger.debug("Assert referer " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + " :: referer: '" +
                        httpServletRequest.getHeader(HEADER_REFERER) + "' vs server & context: " + currentServer + " (string)" +
                        (referer != null ? " or " + referer + " (regexp)" : "")
                );

            // Note! Add slashes at the end to avoid missing when the victim's domain is "site.com"
            // and the attacker site "site.com.attacker.com"
            if (!currentServer.endsWith("/"))
            {
                currentServer += "/";
            }

            if (refererHeader.isEmpty() && !always)
            {
                // The referrer header might be blank or no existing due to a variety of "valid" reasons, i.e:
                // * If a website is accessed from a HTTP Secure (HTTPS) connection and a link points to anywhere except
                //   another secure location, then the referrer field is not sent.
                // * A proxy or other system might have blanked the header due to privacy concerns sending the entire
                //   url including the full path.
                // * The user agent might have been instructed to not send the referrer header using "noreferrer".
            }
            else
            {
                boolean valid = false;
                if (refererHeader.startsWith(currentServer))
                {
                    valid = true;
                }
                if (referer != null && !referer.isEmpty() && refererHeader.matches(referer))
                {
                    valid = true;
                }
                if (!valid)
                {
                    String message = "Possible CSRF attack noted when asserting referer header '" +
                            httpServletRequest.getHeader(HEADER_REFERER) + "'. Request: " + httpServletRequest.getMethod() + " " +
                            httpServletRequest.getRequestURI();
                    if (logger.isInfoEnabled())
                        logger.info(message);

                    message += ", FAILED TEST: Assert referer " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + " :: referer: '" +
                            httpServletRequest.getHeader(HEADER_REFERER) + "' vs server & context: " + currentServer + " (string)" +
                    (refererServer != null ? " or " + refererServer + " (regexp)" : "");
                    
                    throw new ServletException(message);
                }
            }
        }
    }


    /**
     * An action that asserts the request's 'Origin' header matches the current server name or the "origin" param .
     */
    private class AssertOriginAction extends Action
    {
        public static final String PARAM_ALWAYS = "always";
        public static final String PARAM_ORIGIN = "origin";
        public static final String HEADER_ORIGIN = "Origin";

        private boolean always = false;
        private String origin = null;

        /**
         * Requires the following params; a boolean deciding if the origin header MUST be present when validated.
         * Defined in a param with key "always".
         *
         * @param params The action parameters
         * @throws ServletException
         */
        public void init(Map<String, String> params) throws ServletException
        {
            super.init(params);

            // Check for mandatory parameters
            if (params != null)
            {
                if (params.containsKey(PARAM_ALWAYS))
                {
                    String alwaysParam = params.get(PARAM_ALWAYS);
                    if (!alwaysParam.equals("true") && !alwaysParam.equals("false"))
                    {
                        String message = "Parameter '" + PARAM_ALWAYS + "' must be a boolean and be set to true or false.";
                        if (logger.isErrorEnabled())
                            logger.error(message);
                        throw new ServletException(message);
                    }
                    always = alwaysParam.equals("true");
                }
                if (params.containsKey(PARAM_ORIGIN))
                {
                    origin = params.get(PARAM_ORIGIN);
                }
            }

            // Override the xml by alfresco-global.properties
            if (globalProperties != null)
            {
                for (Map.Entry<Object, Object> globalEntry : globalProperties.entrySet())
                {
                    String property = (String) globalEntry.getKey();
                    if (property.startsWith(PROPERTY_PREFIX))
                    {
                        if (property.replaceFirst(PROPERTY_PREFIX, "").equals(PARAM_ORIGIN))
                        {
                            origin = (String) globalEntry.getValue();
                        }
                        else if (property.replaceFirst(PROPERTY_PREFIX, "")
                                .equals(PARAM_ORIGIN + "." + PARAM_ALWAYS))
                        {
                            always = Boolean.valueOf((String) globalEntry.getValue());
                        }
                    }
                }
            }
        }

        public void run(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpSession httpSession) throws ServletException
        {
            String originHeader = httpServletRequest.getHeader(HEADER_ORIGIN);
            if (originHeader == null)
            {
                originHeader = "";
            }

            String currentServer = getServerString(httpServletRequest);

            if (logger.isDebugEnabled())
                logger.debug("Assert origin " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + " :: origin: '" +
                        httpServletRequest.getHeader(HEADER_ORIGIN) + "' vs server: " + currentServer + " (string)" +
                        (origin != null ? " or " + origin + " (regexp)" : ""));

            if (originHeader.isEmpty() && !always)
            {
                // Only valid reason for the Origin header not being sent should be due to an old browser NOT supporting it.
            }
            else
            {
                boolean valid = false;
                if (originHeader.startsWith(currentServer))
                {
                    valid = true;
                }
                if (origin != null && !origin.isEmpty() && originHeader.matches(origin))
                {
                    valid = true;
                }
                if (!valid)
                {
                    String message = "Possible CSRF attack noted when asserting origin header '" + httpServletRequest.getHeader(HEADER_ORIGIN) + "'. " +
                            "Request: " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI();
                    if (logger.isInfoEnabled())
                        logger.info(message);

                    throw new ServletException(message);
                }
            }
        }
    }


    /**
     * An action that asserts the request's 'Origin' header matches the current server name or the "origin" param .
     */
    private class ThrowErrorAction extends Action
    {
        public static final String PARAM_MESSAGE = "message";

        private String message = "Request is not allowed to be executed.";

        /**
         * Requires the following params; a boolean deciding if the origin header MUST be present when validated.
         * Defined in a param with key "always".
         *
         * @param params The action parameters
         * @throws ServletException
         */
        public void init(Map<String, String> params) throws ServletException
        {
            super.init(params);

            if (params != null)
            {
                if (params.containsKey("message"))
                {
                    message = params.get(PARAM_MESSAGE);
                }
            }
        }

        public void run(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, HttpSession httpSession) throws ServletException
        {
            String str = message + " Request: " + httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI();
            if (logger.isInfoEnabled())
                logger.info(str);

            throw new ServletException(str);
        }
    }
}
