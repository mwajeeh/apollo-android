package com.apollographql.android.impl;

import com.apollographql.android.ApolloCall;
import com.apollographql.android.ApolloPrefetch;
import com.apollographql.android.CustomTypeAdapter;
import com.apollographql.android.api.graphql.Operation;
import com.apollographql.android.api.graphql.ResponseFieldMapper;
import com.apollographql.android.api.graphql.ScalarType;
import com.apollographql.android.cache.http.EvictionStrategy;
import com.apollographql.android.cache.http.HttpCache;
import com.apollographql.android.cache.http.HttpCacheControl;
import com.apollographql.android.cache.http.ResponseCacheStore;
import com.apollographql.android.cache.normalized.Cache;
import com.apollographql.android.cache.normalized.CacheControl;
import com.apollographql.android.cache.normalized.CacheKeyResolver;
import com.apollographql.android.cache.normalized.CacheStore;
import com.apollographql.android.cache.normalized.RealCache;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import static com.apollographql.android.api.graphql.util.Utils.checkNotNull;

public final class ApolloClient implements ApolloCall.Factory {
  public static Builder builder() {
    return new Builder();
  }

  private final HttpUrl serverUrl;
  private final Call.Factory httpCallFactory;
  private final HttpCache httpCache;
  private final Cache cache;
  private final Map<ScalarType, CustomTypeAdapter> customTypeAdapters;
  private final Moshi moshi;
  private final Map<Class, ResponseFieldMapper> responseFieldMapperPool = new LinkedHashMap<>();
  private final ExecutorService dispatcher;
  private final HttpCacheControl defaultHttpCacheControl;
  private final CacheControl defaultCacheControl;

  private ApolloClient(Builder builder) {
    this.serverUrl = builder.serverUrl;
    this.httpCallFactory = builder.okHttpClient;
    this.httpCache = builder.httpCache;
    this.cache = builder.cache;
    this.customTypeAdapters = builder.customTypeAdapters;
    this.moshi = builder.moshiBuilder.build();
    this.dispatcher = builder.dispatcher;
    this.defaultHttpCacheControl = builder.defaultHttpCacheControl;
    this.defaultCacheControl = builder.defaultCacheControl;
  }

  @Override
  public <D extends Operation.Data, T, V extends Operation.Variables> ApolloCall<T> newCall(
      @Nonnull Operation<D, T, V> operation) {
    ResponseFieldMapper responseFieldMapper;
    synchronized (responseFieldMapperPool) {
      responseFieldMapper = responseFieldMapperPool.get(operation.getClass());
      if (responseFieldMapper == null) {
        responseFieldMapper = operation.responseFieldMapper();
        responseFieldMapperPool.put(operation.getClass(), responseFieldMapper);
      }
    }
    return new RealApolloCall<T>(operation, serverUrl, httpCallFactory, httpCache, moshi, responseFieldMapper,
        customTypeAdapters, cache, dispatcher)
        .httpCacheControl(defaultHttpCacheControl)
        .cacheControl(defaultCacheControl);
  }

  @Override
  public <D extends Operation.Data, T, V extends Operation.Variables> ApolloPrefetch prefetch(
      @Nonnull Operation<D, T, V> operation) {
    return new RealApolloPrefetch(operation, serverUrl, httpCallFactory, moshi);
  }

  void clearCache() {
    if (httpCache != null) {
      httpCache.clear();
    }
  }

  Response cachedHttpResponse(String cacheKey) throws IOException {
    if (httpCache != null) {
      return httpCache.read(cacheKey);
    } else {
      return null;
    }
  }

  @SuppressWarnings("WeakerAccess") public static class Builder {
    OkHttpClient okHttpClient;
    HttpUrl serverUrl;
    HttpCache httpCache;
    Cache cache = Cache.NO_CACHE;
    HttpCacheControl defaultHttpCacheControl = HttpCacheControl.CACHE_FIRST;
    CacheControl defaultCacheControl = CacheControl.CACHE_FIRST;
    final Map<ScalarType, CustomTypeAdapter> customTypeAdapters = new LinkedHashMap<>();
    final Moshi.Builder moshiBuilder = new Moshi.Builder();
    ExecutorService dispatcher;

    private Builder() {
    }

    public Builder okHttpClient(@Nonnull OkHttpClient okHttpClient) {
      this.okHttpClient = checkNotNull(okHttpClient, "okHttpClient is null");
      return this;
    }

    public Builder serverUrl(@Nonnull HttpUrl serverUrl) {
      this.serverUrl = checkNotNull(serverUrl, "serverUrl is null");
      return this;
    }

    public Builder serverUrl(@Nonnull String serverUrl) {
      this.serverUrl = HttpUrl.parse(checkNotNull(serverUrl, "serverUrl == null"));
      return this;
    }

    public Builder httpCache(@Nonnull ResponseCacheStore cacheStore, @Nonnull EvictionStrategy evictionStrategy) {
      this.httpCache = new HttpCache(checkNotNull(cacheStore, "cacheStore == null"),
          checkNotNull(evictionStrategy, "baseUrl == null"));
      return this;
    }

    public Builder normalizedCache(@Nonnull CacheStore cacheStore,
        @Nonnull CacheKeyResolver<Map<String, Object>> cacheKeyResolver) {
      this.cache = new RealCache(checkNotNull(cacheStore, "cacheStore == null"),
          checkNotNull(cacheKeyResolver, "cacheKeyResolver == null"));
      return this;
    }

    public <T> Builder withCustomTypeAdapter(@Nonnull ScalarType scalarType,
        @Nonnull final CustomTypeAdapter<T> customTypeAdapter) {
      customTypeAdapters.put(scalarType, customTypeAdapter);
      moshiBuilder.add(scalarType.javaType(), new JsonAdapter<T>() {
        @Override
        public T fromJson(com.squareup.moshi.JsonReader reader) throws IOException {
          return customTypeAdapter.decode(reader.nextString());
        }

        @Override
        public void toJson(JsonWriter writer, T value) throws IOException {
          //noinspection unchecked
          writer.value(customTypeAdapter.encode(value));
        }
      });
      return this;
    }

    public Builder dispatcher(@Nonnull ExecutorService dispatcher) {
      this.dispatcher = checkNotNull(dispatcher, "dispatcher == null");
      return this;
    }

    public Builder defaultHttpCacheControl(@Nonnull HttpCacheControl cacheControl) {
      this.defaultHttpCacheControl = checkNotNull(cacheControl, "cacheControl == null");
      return this;
    }

    public Builder defaultCacheControl(@Nonnull CacheControl cacheControl) {
      this.defaultCacheControl = checkNotNull(cacheControl, "cacheControl == null");
      return this;
    }

    public ApolloClient build() {
      checkNotNull(okHttpClient, "okHttpClient is null");
      checkNotNull(serverUrl, "serverUrl is null");

      if (httpCache != null) {
        okHttpClient = okHttpClient.newBuilder().addInterceptor(httpCache.interceptor()).build();
      }

      if (dispatcher == null) {
        dispatcher = defaultDispatcher();
      }

      return new ApolloClient(this);
    }

    private ExecutorService defaultDispatcher() {
      return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
          return new Thread(runnable, "Apollo Dispatcher");
        }
      });
    }
  }
}
