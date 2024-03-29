/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.solr.mcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* SearchComponent plugin for ManifoldCF-specific document-level access control.
* Configuration is under the SolrACLSecurity name.
*/
public class ManifoldCFSearchComponent extends SearchComponent implements SolrCoreAware
{
  /** The component name */
  static final public String COMPONENT_NAME = "mcf";
  /** The parameter that is supposed to contain the authenticated user name, possibly including the AD domain */
  static final public String AUTHENTICATED_USER_NAME = "AuthenticatedUserName";
  /** The parameter that is supposed to contain the MCF authorization domain, if any */
  static final public String AUTHENTICATED_USER_DOMAIN = "AuthenticatedUserDomain";
  /** If there are more than one user/domain, this prefix will allow us to get the users... */
  static final public String AUTHENTICATED_USER_NAME_PREFIX = "AuthenticatedUserName_";
  /** If there are more than one user/domain, this prefix will allow us to get the authorization domains... */
  static final public String AUTHENTICATED_USER_DOMAIN_PREFIX = "AuthenticatedUserDomain_";

  /** This parameter is an array of strings, which contain the tokens to use if there is no authenticated user name.
   * It's meant to work with mod_authz_annotate,
   * running under Apache */
  static final public String USER_TOKENS = "UserTokens";
  
  /** Special token for null security fields */
  static final public String NOSECURITY_TOKEN = "__nosecurity__";
  
  /** The queries that we will not attempt to interfere with */
  static final private String[] globalAllowed = { "solrpingquery" };
  
  /** A logger we can use */
  private static final Logger LOG = LoggerFactory.getLogger(ManifoldCFSearchComponent.class);

  // Member variables
  String authorityBaseURL = null;
  String fieldAllowDocument = null;
  String fieldDenyDocument = null;
  String fieldAllowShare = null;
  String fieldDenyShare = null;
  String fieldAllowParent = null;
  String fieldDenyParent = null;
  int connectionTimeOut;
  int socketTimeOut;
  PoolingHttpClientConnectionManager httpConnectionManager = null;
  HttpClient client = null;
  int poolSize;
  
  public ManifoldCFSearchComponent()
  {
    super();
  }

  @Override
  public void init(NamedList args)
  {
    super.init(args);
    authorityBaseURL = (String)args.get("AuthorityServiceBaseURL");
    if (authorityBaseURL == null)
    {
      LOG.info("USING DEFAULT BASE URL!!");
      authorityBaseURL = "http://localhost:8345/mcf-authority-service";
    }
    Integer cTimeOut = (Integer)args.get("ConnectionTimeOut");
    connectionTimeOut = cTimeOut == null ? 60000 : cTimeOut;
    Integer timeOut = (Integer)args.get("SocketTimeOut");
    socketTimeOut = timeOut == null ? 300000 : timeOut;
    String allowAttributePrefix = (String)args.get("AllowAttributePrefix");
    String denyAttributePrefix = (String)args.get("DenyAttributePrefix");
    if (allowAttributePrefix == null)
      allowAttributePrefix = "allow_token_";
    if (denyAttributePrefix == null)
      denyAttributePrefix = "deny_token_";
    fieldAllowDocument = allowAttributePrefix+"document";
    fieldDenyDocument = denyAttributePrefix+"document";
    fieldAllowShare = allowAttributePrefix+"share";
    fieldDenyShare = denyAttributePrefix+"share";
    fieldAllowParent = allowAttributePrefix+"parent";
    fieldDenyParent = denyAttributePrefix+"parent";
    Integer connectionPoolSize = (Integer)args.get("ConnectionPoolSize");
    poolSize = (connectionPoolSize==null)?50:connectionPoolSize;

    // Initialize the connection pool
    httpConnectionManager = new PoolingHttpClientConnectionManager();
    httpConnectionManager.setMaxTotal(poolSize);
    httpConnectionManager.setDefaultMaxPerRoute(poolSize);
    httpConnectionManager.setValidateAfterInactivity(2000);
    httpConnectionManager.setDefaultSocketConfig(SocketConfig.custom()
            .setTcpNoDelay(true)
            .setSoTimeout(socketTimeOut)
            .build());

    RequestConfig.Builder requestBuilder = RequestConfig.custom()
            .setCircularRedirectsAllowed(true)
            .setSocketTimeout(socketTimeOut)
            .setExpectContinueEnabled(true)
            .setConnectTimeout(connectionTimeOut)
            .setConnectionRequestTimeout(socketTimeOut);

    HttpClientBuilder clientBuilder = HttpClients.custom()
            .setConnectionManager(httpConnectionManager)
            .disableAutomaticRetries()
            .setDefaultRequestConfig(requestBuilder.build())
            .setRedirectStrategy(new DefaultRedirectStrategy());           

    client = clientBuilder.build();

  }

  @Override
  public void prepare(ResponseBuilder rb) throws IOException
  {
    SolrParams params = rb.req.getParams();
    if (!params.getBool(COMPONENT_NAME, true) || params.get(ShardParams.SHARDS) != null)
      return;
    
    // Log that we got here
    //LOG.info("prepare() entry params:\n" + params + "\ncontext: " + rb.req.getContext());
		
    String qry = params.get(CommonParams.Q);
    if (qry != null)
    {
      //Check global allowed searches
      for (String ga : globalAllowed)
      {
        if (qry.equalsIgnoreCase(ga.trim()))
          // Allow this query through unchanged
          return;
      }
    }

    List<String> userAccessTokens;
    
    // Map from domain to user
    Map<String,String> domainMap = new HashMap<>();
      
    // Get the authenticated user name from the parameters
    String authenticatedUserName = params.get(AUTHENTICATED_USER_NAME);
    if (authenticatedUserName != null)
    {
      String authenticatedUserDomain = params.get(AUTHENTICATED_USER_DOMAIN);
      if (authenticatedUserDomain == null)
        authenticatedUserDomain = "";
      domainMap.put(authenticatedUserDomain, authenticatedUserName);
    }
    else
    {
      // Look for user names/domains using the prefix
      int i = 0;
      while (true)
      {
        String userName = params.get(AUTHENTICATED_USER_NAME_PREFIX+i);
        String domain = params.get(AUTHENTICATED_USER_DOMAIN_PREFIX+i);
        if (userName == null)
          break;
        if (domain == null)
          domain = "";
        domainMap.put(domain,userName);
        i++;
      }
    }
      
    // If this parameter is empty or does not exist, we have to presume this is a guest, and treat them accordingly
    if (domainMap.size() == 0)
    {
      // No authenticated user name.
      // mod_authz_annotate may be in use upstream, so look for tokens from it.
      userAccessTokens = new ArrayList<>();
      String[] passedTokens = params.getParams(USER_TOKENS);
      if (passedTokens == null)
      {
        // Only return 'public' documents (those with no security tokens at all)
        LOG.info("Default no-user response (open documents only)");
      }
      else
      {
        // Only return 'public' documents (those with no security tokens at all)
        LOG.info("Group tokens received from caller");
        userAccessTokens.addAll(Arrays.asList(passedTokens));
      }
    }
    else
    {
      if(LOG.isInfoEnabled()){
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String domain : domainMap.keySet())
        {
          if (!first)
            sb.append(",");
          else
            first = false;
          sb.append(domain).append(":").append(domainMap.get(domain));
        }
        sb.append("]");
        LOG.info("Trying to match docs for user '"+sb.toString()+"'");
      }
      // Valid authenticated user name.  Look up access tokens for the user.
      // Check the configuration arguments for validity
      if (authorityBaseURL == null)
      {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error initializing ManifoldCFSecurityFilter component: 'AuthorityServiceBaseURL' init parameter required");
      }
      userAccessTokens = getAccessTokens(domainMap);
    }

    BooleanQuery.Builder bq = new BooleanQuery.Builder();
    //bf.setMaxClauseCount(100000);
    
    Query allowShareOpen = new TermQuery(new Term(fieldAllowShare,NOSECURITY_TOKEN));
    Query denyShareOpen = new TermQuery(new Term(fieldDenyShare,NOSECURITY_TOKEN));
    Query allowParentOpen = new TermQuery(new Term(fieldAllowParent,NOSECURITY_TOKEN));
    Query denyParentOpen = new TermQuery(new Term(fieldDenyParent,NOSECURITY_TOKEN));
    Query allowDocumentOpen = new TermQuery(new Term(fieldAllowDocument,NOSECURITY_TOKEN));
    Query denyDocumentOpen = new TermQuery(new Term(fieldDenyDocument,NOSECURITY_TOKEN));
    
    if (userAccessTokens.size() == 0)
    {
      // Only open documents can be included.
      // That query is:
      // (fieldAllowShare is empty AND fieldDenyShare is empty AND fieldAllowDocument is empty AND fieldDenyDocument is empty)
      // We're trying to map to:  -(fieldAllowShare:*) , which should be pretty efficient in Solr because it is negated.  If this turns out not to be so, then we should
      // have the SolrConnector inject a special token into these fields when they otherwise would be empty, and we can trivially match on that token.
      bq.add(allowShareOpen,BooleanClause.Occur.MUST);
      bq.add(denyShareOpen,BooleanClause.Occur.MUST);
      bq.add(allowParentOpen,BooleanClause.Occur.MUST);
      bq.add(denyParentOpen,BooleanClause.Occur.MUST);
      bq.add(allowDocumentOpen,BooleanClause.Occur.MUST);
      bq.add(denyDocumentOpen,BooleanClause.Occur.MUST);
    }
    else
    {
      // Extend the query appropriately for each user access token.
      bq.add(calculateCompleteSubquery(fieldAllowShare,fieldDenyShare,allowShareOpen,denyShareOpen,userAccessTokens),
        BooleanClause.Occur.MUST);
      bq.add(calculateCompleteSubquery(fieldAllowParent,fieldDenyParent,allowParentOpen,denyParentOpen,userAccessTokens),
        BooleanClause.Occur.MUST);
      bq.add(calculateCompleteSubquery(fieldAllowDocument,fieldDenyDocument,allowDocumentOpen,denyDocumentOpen,userAccessTokens),
        BooleanClause.Occur.MUST);
    }

    // Concatenate with the user's original query.
    List<Query> list = rb.getFilters();
    if (list == null)
    {
      list = new ArrayList<>();
      rb.setFilters(list);
    }
    list.add(new ConstantScoreQuery(bq.build()));
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException
  {
    //LOG.info("process() called");
  }

  /** Calculate a complete subclause, representing something like:
  * ((fieldAllowShare is empty AND fieldDenyShare is empty) OR fieldAllowShare HAS token1 OR fieldAllowShare HAS token2 ...)
  *     AND fieldDenyShare DOESN'T_HAVE token1 AND fieldDenyShare DOESN'T_HAVE token2 ...
  */
  protected Query calculateCompleteSubquery(String allowField, String denyField, Query allowOpen, Query denyOpen, List<String> userAccessTokens)
  {
    BooleanQuery.Builder bq = new BooleanQuery.Builder();
    BooleanQuery.setMaxClauseCount(1000000);
    
    // Add the empty-acl case
    BooleanQuery.Builder subUnprotectedClause = new BooleanQuery.Builder();
    subUnprotectedClause.add(allowOpen,BooleanClause.Occur.MUST);
    subUnprotectedClause.add(denyOpen,BooleanClause.Occur.MUST);
    bq.add(subUnprotectedClause.build(),BooleanClause.Occur.SHOULD);
    for (String accessToken : userAccessTokens)
    {
      bq.add(new TermQuery(new Term(allowField,accessToken)),BooleanClause.Occur.SHOULD);
      bq.add(new TermQuery(new Term(denyField,accessToken)),BooleanClause.Occur.MUST_NOT);
    }
    return bq.build();
  }
  
  //---------------------------------------------------------------------------------
  // SolrInfoMBean
  //---------------------------------------------------------------------------------
  @Override
  public String getDescription()
  {
    return "ManifoldCF Solr security enforcement plugin";
  }

  @Override
  public Category getCategory()
  {
    return Category.QUERY;
  }

  @Override
  public void inform(SolrCore core)
  {
    core.addCloseHook(new CloseHandler());
  }
  
  // Protected methods
  
  /** Get access tokens given a username */
  protected List<String> getAccessTokens(Map<String,String> domainMap)
    throws IOException
  {
    // We can make this more complicated later, with support for https etc., but this is enough to demonstrate how it all should work.
    StringBuilder urlBuffer = new StringBuilder(authorityBaseURL);
    urlBuffer.append("/UserACLs");
    int i = 0;
    for (String domain : domainMap.keySet())
    {
      if (i == 0)
        urlBuffer.append("?");
      else
        urlBuffer.append("&");
      // For backwards compatibility, handle the singleton case specially
      if (domainMap.size() == 1 && domain.length() == 0)
      {
        urlBuffer.append("username=").append(URLEncoder.encode(domainMap.get(domain),"utf-8"));
      }
      else
      {
        urlBuffer.append("username_").append(Integer.toString(i)).append("=").append(URLEncoder.encode(domainMap.get(domain),"utf-8")).append("&")
          .append("domain_").append(Integer.toString(i)).append("=").append(URLEncoder.encode(domain,"utf-8"));
      }
      i++;
    }
    String theURL = urlBuffer.toString();
        
    HttpGet method = new HttpGet(theURL);
    try
    {
      HttpResponse httpResponse = client.execute(method);
      int rval = httpResponse.getStatusLine().getStatusCode();
      if (rval != 200)
      {
        String response = EntityUtils.toString(httpResponse.getEntity(), StandardCharsets.UTF_8);
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,"Couldn't fetch user's access tokens from ManifoldCF authority service: "+Integer.toString(rval)+"; "+response);
      }

      try(InputStream is = httpResponse.getEntity().getContent())
      {
        Charset charSet;
        try
        {
          ContentType ct = ContentType.get(httpResponse.getEntity());
          if (ct == null)
            charSet = StandardCharsets.UTF_8;
          else
            charSet = ct.getCharset();
        }catch (ParseException e){
          charSet = StandardCharsets.UTF_8;
        }


        try(Reader r = new InputStreamReader(is,charSet); BufferedReader br = new BufferedReader(r))
        {
            // Read the tokens, one line at a time.  If any authorities are down, we have no current way to note that, but someday we will.
            List<String> tokenList = new ArrayList<>();
            while (true)
            {
              String line = br.readLine();
              if (line == null)
                break;
              if (line.startsWith("TOKEN:"))
              {
                tokenList.add(line.substring("TOKEN:".length()));
              }
              else
              {
                // It probably says something about the state of the authority(s) involved, so log it
                LOG.info("Saw authority response "+line);
              }
            }
            return tokenList;
          }
      }
    }
    finally
    {
      method.abort();
    }
  }
  
  /** CloseHook implementation.
  */
  protected class CloseHandler implements CloseHook
  {
    public CloseHandler()
    {
    }
    
    @Override
    public void preClose(SolrCore core)
    {
    }
    
    @Override
    public void postClose(SolrCore core)
    {
      // Close the connection pool
      if (httpConnectionManager != null)
      {
        httpConnectionManager.shutdown();
        httpConnectionManager = null;
        client = null;
      }
    }
    
  }
  
}