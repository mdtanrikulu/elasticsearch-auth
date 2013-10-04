package org.codelibs.elasticsearch.auth.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.codelibs.elasticsearch.auth.AuthException;
import org.codelibs.elasticsearch.auth.filter.ContentFilter;
import org.codelibs.elasticsearch.auth.filter.LoginFilter;
import org.codelibs.elasticsearch.auth.filter.LogoutFilter;
import org.codelibs.elasticsearch.auth.security.Authenticator;
import org.codelibs.elasticsearch.auth.security.IndexAuthenticator;
import org.codelibs.elasticsearch.auth.security.LoginConstraint;
import org.codelibs.elasticsearch.auth.util.MapUtil;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;

public class AuthService extends AbstractLifecycleComponent<AuthService> {
    private RestController restController;

    private Map<String, Authenticator> authenticatorMap = new LinkedHashMap<String, Authenticator>();

    private Client client;

    private String constraintIndex;

    private String constraintType;

    private String authTokenIndex = "auth";

    private String tokenType = "token";

    private String tokenKey = "token";

    private ContentFilter contentFilter;

    private long sessionTimeout = 1000 * 60 * 30; // 30min

    @Inject
    public AuthService(final Settings settings, final Client client,
            final RestController restController) {
        super(settings);
        this.client = client;
        this.restController = restController;

        logger.info("Creating authenticators.");

        constraintIndex = settings.get("auth.constraint.index", "security");
        constraintType = settings.get("auth.constraint.type", "constraint");
        sessionTimeout = settings.getAsLong("auth.token.timeout",
                Long.valueOf(1000 * 60 * 30));

        // Default 
        final IndexAuthenticator indexAuthenticator = new IndexAuthenticator(
                client);
        final String indexName = settings.get("auth.authenticator.index.index");
        if (indexName != null) {
            indexAuthenticator.setIndex(indexName);
        }
        final String typeName = settings.get("auth.authenticator.index.type");
        if (typeName != null) {
            indexAuthenticator.setType(typeName);
        }
        registerAuthenticator("index", indexAuthenticator);
    }

    @Override
    protected void doStart() throws ElasticSearchException {
        logger.info("Starting AuthService.");

        final LoginFilter loginFilter = new LoginFilter(this, authenticatorMap);
        final String loginPath = settings.get("auth.login.path");
        if (loginPath != null) {
            loginFilter.setLoginPath(loginPath);
        }
        final String[] loginMethodValues = settings
                .getAsArray("auth.login.methods");
        if (loginMethodValues != null && loginMethodValues.length > 0) {
            loginFilter.setHttpMethods(createMethods(loginMethodValues));
        }
        restController.registerFilter(loginFilter);

        final LogoutFilter logoutFilter = new LogoutFilter(this);
        final String logoutPath = settings.get("auth.logout.path");
        if (logoutPath != null) {
            logoutFilter.setLogoutPath(logoutPath);
        }
        final String[] logoutMethodValues = settings
                .getAsArray("auth.logout.methods");
        if (logoutMethodValues != null && logoutMethodValues.length > 0) {
            logoutFilter.setHttpMethods(createMethods(logoutMethodValues));
        }
        restController.registerFilter(logoutFilter);

        contentFilter = new ContentFilter(this);
        restController.registerFilter(contentFilter);
    }

    @Override
    protected void doStop() throws ElasticSearchException {
        logger.info("Stopping AuthService");
    }

    @Override
    protected void doClose() throws ElasticSearchException {
        logger.info("Closing AuthService.");
    }

    public void registerAuthenticator(final String name,
            final Authenticator authenticator) {
        authenticatorMap.put(name, authenticator);
    }

    public void reload() {
        // TODO authenticators
        final LoginConstraint[] constraints = getLoginConstraints();
        contentFilter.setLoginConstraints(constraints);
    }

    public String createToken(final List<String> roleList) {
        final String token = generateToken();

        final Map<String, Object> sourceMap = new HashMap<String, Object>();
        sourceMap.put("roles", roleList);
        sourceMap.put("lastModified", new Date());
        client.prepareIndex(authTokenIndex, tokenType, token)
                .setSource(sourceMap).setRefresh(true).execute().actionGet();
        return token;
    }

    public boolean authenticate(final String token, final String[] roles) {
        if (token != null) {
            final GetResponse response = client
                    .prepareGet(authTokenIndex, tokenType, token).execute()
                    .actionGet();
            final Map<String, Object> sourceMap = response.getSource();
            if (sourceMap != null) {
                final Date lastModified = MapUtil.getAsDate(sourceMap,
                        "lastModified", null);
                if (lastModified == null
                        || System.currentTimeMillis() - lastModified.getTime() > sessionTimeout) {
                    client.prepareDelete(authTokenIndex, tokenType, token)
                            .setRefresh(true).execute().actionGet();
                    return false;
                }
                final String[] tokenRoles = MapUtil.getAsArray(sourceMap,
                        "roles", new String[0]);
                for (final String role : roles) {
                    for (final String tokenRole : tokenRoles) {
                        if (role.equals(tokenRole)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public void createUser(final String authenticatorName,
            final String username, final String password, final String[] roles) {
        getAuthenticator(authenticatorName).createUser(username, password,
                roles);
    }

    public void updateUser(final String authenticatorName,
            final String username, final String password, final String[] roles) {
        getAuthenticator(authenticatorName).updateUser(username, password,
                roles);
    }

    public void deleteUser(final String authenticatorName, final String username) {
        getAuthenticator(authenticatorName).deleteUser(username);
    }

    public void deleteToken(final String token) {
        final DeleteResponse response = client
                .prepareDelete(authTokenIndex, tokenType, token)
                .setRefresh(true).execute().actionGet();
        if (response.isNotFound()) {
            throw new AuthException(RestStatus.BAD_REQUEST,
                    "The token does not exist.");
        }
    }

    public String getToken(final RestRequest request) {
        final String token = request.param(tokenKey);
        // TODO cookie
        return token;
    }

    private String generateToken() {
        return DigestUtils.sha512Hex(UUID.randomUUID().toString());
    }

    private Authenticator getAuthenticator(final String authenticatorName) {
        final Authenticator authenticator = authenticatorMap
                .get(authenticatorName);
        if (authenticator == null) {
            throw new AuthException(RestStatus.BAD_REQUEST,
                    "Unknown authenticator: " + authenticatorName);
        }
        return authenticator;
    }

    private LoginConstraint[] getLoginConstraints() {
        final List<LoginConstraint> constraintList = new ArrayList<LoginConstraint>();
        try {
            final SearchResponse response = client
                    .prepareSearch(constraintIndex).setTypes(constraintType)
                    .setQuery(QueryBuilders.queryString("*:*")).execute()
                    .actionGet();
            final SearchHits hits = response.getHits();
            if (hits.totalHits() != 0) {
                for (final SearchHit hit : hits) {
                    final Map<String, Object> sourceMap = hit.sourceAsMap();
                    final List<String> pathList = MapUtil.getAsList(sourceMap,
                            "paths", Collections.<String> emptyList());
                    final List<String> roleList = MapUtil.getAsList(sourceMap,
                            "roles", Collections.<String> emptyList());
                    final String authName = MapUtil.getAsString(sourceMap,
                            "authenticator", null);
                    final Authenticator authenticator = authenticatorMap
                            .get(authName);
                    if (!pathList.isEmpty() && !roleList.isEmpty()
                            && authenticator != null) {
                        for (final String path : pathList) {
                            final LoginConstraint loginLogic = new LoginConstraint();
                            loginLogic.setPath(path);
                            loginLogic.setRoles(roleList.toArray(roleList
                                    .toArray(new String[roleList.size()])));
                            constraintList.add(loginLogic);
                        }
                    } else {
                        logger.warn("Invaid login settings: " + sourceMap);
                    }
                }
            }

            if (!constraintList.isEmpty()) {
                Collections.sort(constraintList,
                        new Comparator<LoginConstraint>() {
                            @Override
                            public int compare(final LoginConstraint o1,
                                    final LoginConstraint o2) {
                                final String path1 = o1.getPath();
                                final String path2 = o2.getPath();
                                final int length1 = path1.length();
                                final int length2 = path2.length();
                                if (length1 == length2) {
                                    return path1.compareTo(path2) > 0 ? 1 : -1;
                                }
                                return length1 < length2 ? -1 : 1;
                            }
                        });
            }

            return constraintList.toArray(new LoginConstraint[constraintList
                    .size()]);

        } catch (final IndexMissingException e) {
            logger.error(constraintIndex + "/" + constraintType
                    + " is not found.", e);
            throw new AuthException(RestStatus.INTERNAL_SERVER_ERROR,
                    constraintIndex + ";" + constraintType + " is not found.");
        }

    }

    private Method[] createMethods(final String[] methodValues) {
        final List<Method> methodList = new ArrayList<Method>();
        for (final String method : methodValues) {
            if ("get".equalsIgnoreCase(method)) {
                methodList.add(Method.GET);
            } else if ("post".equalsIgnoreCase(method)) {
                methodList.add(Method.POST);
            } else if ("head".equalsIgnoreCase(method)) {
                methodList.add(Method.HEAD);
            } else if ("options".equalsIgnoreCase(method)) {
                methodList.add(Method.OPTIONS);
            } else if ("put".equalsIgnoreCase(method)) {
                methodList.add(Method.PUT);
            } else if ("delete".equalsIgnoreCase(method)) {
                methodList.add(Method.DELETE);
            }
        }
        return methodList.toArray(new Method[methodList.size()]);
    }

}
