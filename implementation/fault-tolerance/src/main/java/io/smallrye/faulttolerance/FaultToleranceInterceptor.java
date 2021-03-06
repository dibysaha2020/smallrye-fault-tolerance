/*
 * Copyright 2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.smallrye.faulttolerance;

import static io.smallrye.faulttolerance.core.Invocation.invocation;
import static io.smallrye.faulttolerance.core.util.CompletionStages.failedStage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.PrivilegedActionException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.annotation.Priority;
import javax.enterprise.context.control.RequestContextController;
import javax.enterprise.inject.Intercepted;
import javax.enterprise.inject.spi.Bean;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;

import io.smallrye.faulttolerance.config.BulkheadConfig;
import io.smallrye.faulttolerance.config.CircuitBreakerConfig;
import io.smallrye.faulttolerance.config.FallbackConfig;
import io.smallrye.faulttolerance.config.FaultToleranceOperation;
import io.smallrye.faulttolerance.config.GenericConfig;
import io.smallrye.faulttolerance.config.RetryConfig;
import io.smallrye.faulttolerance.config.TimeoutConfig;
import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.async.CompletionStageExecution;
import io.smallrye.faulttolerance.core.async.FutureExecution;
import io.smallrye.faulttolerance.core.async.RememberEventLoop;
import io.smallrye.faulttolerance.core.bulkhead.CompletionStageThreadPoolBulkhead;
import io.smallrye.faulttolerance.core.bulkhead.FutureThreadPoolBulkhead;
import io.smallrye.faulttolerance.core.bulkhead.SemaphoreBulkhead;
import io.smallrye.faulttolerance.core.circuit.breaker.CircuitBreaker;
import io.smallrye.faulttolerance.core.circuit.breaker.CompletionStageCircuitBreaker;
import io.smallrye.faulttolerance.core.event.loop.EventLoop;
import io.smallrye.faulttolerance.core.fallback.AsyncFallbackFunction;
import io.smallrye.faulttolerance.core.fallback.CompletionStageFallback;
import io.smallrye.faulttolerance.core.fallback.Fallback;
import io.smallrye.faulttolerance.core.fallback.FallbackFunction;
import io.smallrye.faulttolerance.core.metrics.CompletionStageMetricsCollector;
import io.smallrye.faulttolerance.core.metrics.MetricsCollector;
import io.smallrye.faulttolerance.core.metrics.MetricsRecorder;
import io.smallrye.faulttolerance.core.retry.CompletionStageRetry;
import io.smallrye.faulttolerance.core.retry.Jitter;
import io.smallrye.faulttolerance.core.retry.RandomJitter;
import io.smallrye.faulttolerance.core.retry.Retry;
import io.smallrye.faulttolerance.core.retry.SimpleBackOff;
import io.smallrye.faulttolerance.core.retry.ThreadSleepDelay;
import io.smallrye.faulttolerance.core.retry.TimerDelay;
import io.smallrye.faulttolerance.core.stopwatch.SystemStopwatch;
import io.smallrye.faulttolerance.core.timeout.AsyncTimeout;
import io.smallrye.faulttolerance.core.timeout.CompletionStageTimeout;
import io.smallrye.faulttolerance.core.timeout.Timeout;
import io.smallrye.faulttolerance.core.timeout.TimerTimeoutWatcher;
import io.smallrye.faulttolerance.core.timer.Timer;
import io.smallrye.faulttolerance.core.util.DirectExecutor;
import io.smallrye.faulttolerance.core.util.SetOfThrowables;
import io.smallrye.faulttolerance.internal.AsyncTypesConversion;
import io.smallrye.faulttolerance.internal.InterceptionPoint;
import io.smallrye.faulttolerance.internal.RequestScopeActivator;
import io.smallrye.faulttolerance.internal.StrategyCache;
import io.smallrye.faulttolerance.metrics.MetricsProvider;

/**
 * The interceptor for fault tolerance strategies.
 *
 * @author Antoine Sabot-Durand
 * @author Martin Kouba
 * @author Michal Szynkiewicz
 */
@Interceptor
@FaultToleranceBinding
@Priority(Interceptor.Priority.PLATFORM_AFTER + 10)
public class FaultToleranceInterceptor {

    private final Bean<?> interceptedBean;

    private final FaultToleranceOperationProvider operationProvider;

    private final StrategyCache cache;

    private final FallbackHandlerProvider fallbackHandlerProvider;

    private final MetricsProvider metricsProvider;

    private final ExecutorService asyncExecutor;

    private final EventLoop eventLoop;

    private final Timer timer;

    private final RequestContextController requestContextController;

    private final CircuitBreakerMaintenanceImpl cbMaintenance;

    @Inject
    public FaultToleranceInterceptor(
            @Intercepted Bean<?> interceptedBean,
            FaultToleranceOperationProvider operationProvider,
            StrategyCache cache,
            FallbackHandlerProvider fallbackHandlerProvider,
            MetricsProvider metricsProvider,
            ExecutorHolder executorHolder,
            RequestContextIntegration requestContextIntegration,
            CircuitBreakerMaintenanceImpl cbMaintenance) {
        this.interceptedBean = interceptedBean;
        this.operationProvider = operationProvider;
        this.cache = cache;
        this.fallbackHandlerProvider = fallbackHandlerProvider;
        this.metricsProvider = metricsProvider;
        asyncExecutor = executorHolder.getAsyncExecutor();
        eventLoop = executorHolder.getEventLoop();
        timer = executorHolder.getTimer();
        requestContextController = requestContextIntegration.get();
        this.cbMaintenance = cbMaintenance;
    }

    @AroundInvoke
    public Object interceptCommand(InvocationContext interceptionContext) throws Exception {
        Method method = interceptionContext.getMethod();
        Class<?> beanClass = interceptedBean != null ? interceptedBean.getBeanClass() : method.getDeclaringClass();
        InterceptionPoint point = new InterceptionPoint(beanClass, interceptionContext);

        FaultToleranceOperation operation = operationProvider.get(beanClass, method);

        if ((operation.isAsync() || operation.isAdditionalAsync()) && AsyncTypes.isKnown(operation.getReturnType())) {
            return asyncFlow(operation, interceptionContext, point);
        } else if (operation.isAsync()) {
            return futureFlow(operation, interceptionContext, point);
        } else {
            return syncFlow(operation, interceptionContext, point);
        }
    }

    private Object asyncFlow(FaultToleranceOperation operation, InvocationContext interceptionContext,
            InterceptionPoint point) {
        FaultToleranceStrategy<Object> strategy = cache.getStrategy(point, () -> prepareAsyncStrategy(operation, point));

        io.smallrye.faulttolerance.core.InvocationContext<Object> ctx = invocationContext(interceptionContext);

        try {
            return strategy.apply(ctx);
        } catch (Exception e) {
            return AsyncTypes.get(operation.getReturnType()).fromCompletionStage(failedStage(e));
        }
    }

    private <T> T syncFlow(FaultToleranceOperation operation, InvocationContext interceptionContext, InterceptionPoint point)
            throws Exception {
        FaultToleranceStrategy<T> strategy = cache.getStrategy(point, () -> prepareSyncStrategy(operation, point));

        io.smallrye.faulttolerance.core.InvocationContext<T> ctx = invocationContext(interceptionContext);

        return strategy.apply(ctx);
    }

    private <T> Future<T> futureFlow(FaultToleranceOperation operation, InvocationContext interceptionContext,
            InterceptionPoint point) throws Exception {
        FaultToleranceStrategy<Future<T>> strategy = cache.getStrategy(point, () -> prepareFutureStrategy(operation, point));

        io.smallrye.faulttolerance.core.InvocationContext<Future<T>> ctx = invocationContext(interceptionContext);

        return strategy.apply(ctx);
    }

    @SuppressWarnings("unchecked")
    private <T> io.smallrye.faulttolerance.core.InvocationContext<T> invocationContext(InvocationContext interceptionContext) {
        io.smallrye.faulttolerance.core.InvocationContext<T> result = new io.smallrye.faulttolerance.core.InvocationContext<>(
                () -> (T) interceptionContext.proceed());

        result.set(InvocationContext.class, interceptionContext);

        return result;
    }

    private FaultToleranceStrategy<Object> prepareAsyncStrategy(FaultToleranceOperation operation, InterceptionPoint point) {
        // FaultToleranceStrategy expects that the input type and output type are the same, which
        // isn't true for the conversion strategies used below (even though the entire chain does
        // retain the type, the conversions are only intermediate)
        // that's why we use raw types here, to work around this design choice

        FaultToleranceStrategy result = invocation();

        result = new AsyncTypesConversion.ToCompletionStage(result, AsyncTypes.get(operation.getReturnType()));

        result = prepareComplectionStageChain((FaultToleranceStrategy<CompletionStage<Object>>) result, operation, point);

        result = new AsyncTypesConversion.FromCompletionStage(result, AsyncTypes.get(operation.getReturnType()));

        return result;
    }

    private <T> FaultToleranceStrategy<CompletionStage<T>> prepareComplectionStageChain(
            FaultToleranceStrategy<CompletionStage<T>> invocation, FaultToleranceOperation operation, InterceptionPoint point) {

        FaultToleranceStrategy<CompletionStage<T>> result = invocation;

        result = new RequestScopeActivator<>(result, requestContextController);

        Executor executor = operation.isThreadOffloadRequired() ? asyncExecutor : DirectExecutor.INSTANCE;
        result = new CompletionStageExecution<>(result, executor);

        if (operation.hasBulkhead()) {
            BulkheadConfig bulkheadConfig = operation.getBulkhead();
            Integer size = bulkheadConfig.get(BulkheadConfig.VALUE);
            Integer queueSize = bulkheadConfig.get(BulkheadConfig.WAITING_TASK_QUEUE);
            result = new CompletionStageThreadPoolBulkhead<>(result, "Bulkhead[" + point + "]",
                    size, queueSize);
        }

        if (operation.hasTimeout()) {
            long timeoutMs = getTimeInMs(operation.getTimeout(), TimeoutConfig.VALUE, TimeoutConfig.UNIT);
            result = new CompletionStageTimeout<>(result, "Timeout[" + point + "]",
                    timeoutMs,
                    new TimerTimeoutWatcher(timer));
        }

        if (operation.hasCircuitBreaker()) {
            CircuitBreakerConfig cbConfig = operation.getCircuitBreaker();
            long delayInMillis = getTimeInMs(cbConfig, CircuitBreakerConfig.DELAY, CircuitBreakerConfig.DELAY_UNIT);
            result = new CompletionStageCircuitBreaker<>(result, "CircuitBreaker[" + point + "]",
                    getSetOfThrowables(cbConfig, CircuitBreakerConfig.FAIL_ON),
                    getSetOfThrowables(cbConfig, CircuitBreakerConfig.SKIP_ON),
                    delayInMillis,
                    cbConfig.get(CircuitBreakerConfig.REQUEST_VOLUME_THRESHOLD),
                    cbConfig.get(CircuitBreakerConfig.FAILURE_RATIO),
                    cbConfig.get(CircuitBreakerConfig.SUCCESS_THRESHOLD),
                    new SystemStopwatch());

            String cbName = cbConfig.getCircuitBreakerName();
            if (cbName == null) {
                cbName = UUID.randomUUID().toString();
            }
            cbMaintenance.register(cbName, (CircuitBreaker<?>) result);
        }

        if (operation.hasRetry()) {
            RetryConfig retryConf = operation.getRetry();
            long maxDurationMs = getTimeInMs(retryConf, RetryConfig.MAX_DURATION, RetryConfig.DURATION_UNIT);

            long delayMs = getTimeInMs(retryConf, RetryConfig.DELAY, RetryConfig.DELAY_UNIT);

            long jitterMs = getTimeInMs(retryConf, RetryConfig.JITTER, RetryConfig.JITTER_DELAY_UNIT);
            Jitter jitter = jitterMs == 0 ? Jitter.ZERO : new RandomJitter(jitterMs);

            result = new CompletionStageRetry<>(result,
                    "Retry[" + point + "]",
                    getSetOfThrowables(retryConf, RetryConfig.RETRY_ON),
                    getSetOfThrowables(retryConf, RetryConfig.ABORT_ON),
                    (int) retryConf.get(RetryConfig.MAX_RETRIES),
                    maxDurationMs,
                    () -> new TimerDelay(new SimpleBackOff(delayMs, jitter), timer),
                    new SystemStopwatch());
        }

        if (operation.hasFallback()) {
            FallbackConfig fallbackConf = operation.getFallback();
            result = new CompletionStageFallback<>(
                    result,
                    "Fallback[" + point + "]",
                    prepareFallbackFunction(point, operation),
                    getSetOfThrowables(fallbackConf, FallbackConfig.APPLY_ON),
                    getSetOfThrowables(fallbackConf, FallbackConfig.SKIP_ON));
        }

        if (metricsProvider.isEnabled()) {
            result = new CompletionStageMetricsCollector<>(result, getMetricsRecorder(operation, point));
        }

        if (!operation.isThreadOffloadRequired()) {
            result = new RememberEventLoop<>(result, eventLoop);
        }

        return result;
    }

    private <T> FaultToleranceStrategy<T> prepareSyncStrategy(FaultToleranceOperation operation, InterceptionPoint point) {
        FaultToleranceStrategy<T> result = invocation();

        if (operation.hasBulkhead()) {
            BulkheadConfig bulkheadConfig = operation.getBulkhead();
            result = new SemaphoreBulkhead<>(result,
                    "Bulkhead[" + point + "]",
                    bulkheadConfig.get(BulkheadConfig.VALUE));
        }

        if (operation.hasTimeout()) {
            long timeoutMs = getTimeInMs(operation.getTimeout(), TimeoutConfig.VALUE, TimeoutConfig.UNIT);
            result = new Timeout<>(result, "Timeout[" + point + "]",
                    timeoutMs,
                    new TimerTimeoutWatcher(timer));
        }

        if (operation.hasCircuitBreaker()) {
            CircuitBreakerConfig cbConfig = operation.getCircuitBreaker();
            long delayInMillis = getTimeInMs(cbConfig, CircuitBreakerConfig.DELAY, CircuitBreakerConfig.DELAY_UNIT);
            result = new CircuitBreaker<>(result, "CircuitBreaker[" + point + "]",
                    getSetOfThrowables(cbConfig, CircuitBreakerConfig.FAIL_ON),
                    getSetOfThrowables(cbConfig, CircuitBreakerConfig.SKIP_ON),
                    delayInMillis,
                    cbConfig.get(CircuitBreakerConfig.REQUEST_VOLUME_THRESHOLD),
                    cbConfig.get(CircuitBreakerConfig.FAILURE_RATIO),
                    cbConfig.get(CircuitBreakerConfig.SUCCESS_THRESHOLD),
                    new SystemStopwatch());

            String cbName = cbConfig.getCircuitBreakerName();
            if (cbName == null) {
                cbName = UUID.randomUUID().toString();
            }
            cbMaintenance.register(cbName, (CircuitBreaker<?>) result);
        }

        if (operation.hasRetry()) {
            RetryConfig retryConf = operation.getRetry();
            long maxDurationMs = getTimeInMs(retryConf, RetryConfig.MAX_DURATION, RetryConfig.DURATION_UNIT);

            long delayMs = getTimeInMs(retryConf, RetryConfig.DELAY, RetryConfig.DELAY_UNIT);

            long jitterMs = getTimeInMs(retryConf, RetryConfig.JITTER, RetryConfig.JITTER_DELAY_UNIT);
            Jitter jitter = jitterMs == 0 ? Jitter.ZERO : new RandomJitter(jitterMs);

            result = new Retry<>(result,
                    "Retry[" + point + "]",
                    getSetOfThrowables(retryConf, RetryConfig.RETRY_ON),
                    getSetOfThrowables(retryConf, RetryConfig.ABORT_ON),
                    (int) retryConf.get(RetryConfig.MAX_RETRIES),
                    maxDurationMs,
                    () -> new ThreadSleepDelay(new SimpleBackOff(delayMs, jitter)),
                    new SystemStopwatch());
        }

        if (operation.hasFallback()) {
            FallbackConfig fallbackConf = operation.getFallback();
            result = new Fallback<>(
                    result,
                    "Fallback[" + point + "]",
                    prepareFallbackFunction(point, operation),
                    getSetOfThrowables(fallbackConf, FallbackConfig.APPLY_ON),
                    getSetOfThrowables(fallbackConf, FallbackConfig.SKIP_ON));
        }

        if (metricsProvider.isEnabled()) {
            result = new MetricsCollector<>(result, getMetricsRecorder(operation, point), false);
        }

        return result;
    }

    private <T> FaultToleranceStrategy<Future<T>> prepareFutureStrategy(FaultToleranceOperation operation,
            InterceptionPoint point) {
        FaultToleranceStrategy<Future<T>> result = invocation();

        result = new RequestScopeActivator<>(result, requestContextController);

        if (operation.hasBulkhead()) {
            BulkheadConfig bulkheadConfig = operation.getBulkhead();
            int size = bulkheadConfig.get(BulkheadConfig.VALUE);
            int queueSize = bulkheadConfig.get(BulkheadConfig.WAITING_TASK_QUEUE);
            result = new FutureThreadPoolBulkhead<>(result, "Bulkhead[" + point + "]",
                    size, queueSize);
        }

        if (operation.hasTimeout()) {
            long timeoutMs = getTimeInMs(operation.getTimeout(), TimeoutConfig.VALUE, TimeoutConfig.UNIT);
            Timeout<Future<T>> timeout = new Timeout<>(result, "Timeout[" + point + "]",
                    timeoutMs,
                    new TimerTimeoutWatcher(timer));
            result = new AsyncTimeout<>(timeout, asyncExecutor);
        }

        if (operation.hasCircuitBreaker()) {
            CircuitBreakerConfig cbConfig = operation.getCircuitBreaker();
            long delayInMillis = getTimeInMs(cbConfig, CircuitBreakerConfig.DELAY, CircuitBreakerConfig.DELAY_UNIT);
            result = new CircuitBreaker<>(result, "CircuitBreaker[" + point + "]",
                    getSetOfThrowables(cbConfig, CircuitBreakerConfig.FAIL_ON),
                    getSetOfThrowables(cbConfig, CircuitBreakerConfig.SKIP_ON),
                    delayInMillis,
                    cbConfig.get(CircuitBreakerConfig.REQUEST_VOLUME_THRESHOLD),
                    cbConfig.get(CircuitBreakerConfig.FAILURE_RATIO),
                    cbConfig.get(CircuitBreakerConfig.SUCCESS_THRESHOLD),
                    new SystemStopwatch());

            String cbName = cbConfig.getCircuitBreakerName();
            if (cbName == null) {
                cbName = UUID.randomUUID().toString();
            }
            cbMaintenance.register(cbName, (CircuitBreaker<?>) result);
        }

        if (operation.hasRetry()) {
            RetryConfig retryConf = operation.getRetry();
            long maxDurationMs = getTimeInMs(retryConf, RetryConfig.MAX_DURATION, RetryConfig.DURATION_UNIT);

            long delayMs = getTimeInMs(retryConf, RetryConfig.DELAY, RetryConfig.DELAY_UNIT);

            long jitterMs = getTimeInMs(retryConf, RetryConfig.JITTER, RetryConfig.JITTER_DELAY_UNIT);
            Jitter jitter = jitterMs == 0 ? Jitter.ZERO : new RandomJitter(jitterMs);

            result = new Retry<>(result,
                    "Retry[" + point + "]",
                    getSetOfThrowables(retryConf, RetryConfig.RETRY_ON),
                    getSetOfThrowables(retryConf, RetryConfig.ABORT_ON),
                    (int) retryConf.get(RetryConfig.MAX_RETRIES),
                    maxDurationMs,
                    () -> new ThreadSleepDelay(new SimpleBackOff(delayMs, jitter)),
                    new SystemStopwatch());
        }

        if (operation.hasFallback()) {
            FallbackConfig fallbackConf = operation.getFallback();
            result = new Fallback<>(result,
                    "Fallback[" + point + "]",
                    prepareFallbackFunction(point, operation),
                    getSetOfThrowables(fallbackConf, FallbackConfig.APPLY_ON),
                    getSetOfThrowables(fallbackConf, FallbackConfig.SKIP_ON));
        }

        if (metricsProvider.isEnabled()) {
            result = new MetricsCollector<>(result, getMetricsRecorder(operation, point), true);
        }

        result = new FutureExecution<>(result, asyncExecutor);

        return result;
    }

    private <V> FallbackFunction<V> prepareFallbackFunction(InterceptionPoint point, FaultToleranceOperation operation) {
        Method fallbackMethod = null;

        FallbackConfig fallbackConfig = operation.getFallback();
        Class<? extends FallbackHandler<?>> fallback = fallbackConfig.get(FallbackConfig.VALUE);
        String fallbackMethodName = fallbackConfig.get(FallbackConfig.FALLBACK_METHOD);

        if (fallback.equals(org.eclipse.microprofile.faulttolerance.Fallback.DEFAULT.class) && !"".equals(fallbackMethodName)) {
            try {
                Method method = point.method();
                fallbackMethod = SecurityActions.getDeclaredMethod(point.beanClass(), method.getDeclaringClass(),
                        fallbackMethodName, method.getGenericParameterTypes());
                if (fallbackMethod == null) {
                    throw new FaultToleranceException("Could not obtain fallback method " + fallbackMethodName);
                }
                SecurityActions.setAccessible(fallbackMethod);
            } catch (PrivilegedActionException e) {
                throw new FaultToleranceException("Could not obtain fallback method", e);
            }
        }

        Class<?> returnType = operation.getReturnType();

        FallbackFunction<V> fallbackFunction;

        Method fallbackMethodFinal = fallbackMethod;
        if (fallbackMethod != null) {
            boolean isDefault = fallbackMethodFinal.isDefault();
            fallbackFunction = ctx -> {
                InvocationContext interceptionContext = ctx.invocationContext.get(InvocationContext.class);
                ExecutionContextWithInvocationContext executionContext = new ExecutionContextWithInvocationContext(
                        interceptionContext);
                try {
                    V result;
                    if (isDefault) {
                        // Workaround for default methods (used e.g. in MP Rest Client)
                        //noinspection unchecked
                        result = (V) DefaultMethodFallbackProvider.getFallback(fallbackMethodFinal, executionContext);
                    } else {
                        //noinspection unchecked
                        result = (V) fallbackMethodFinal.invoke(interceptionContext.getTarget(),
                                interceptionContext.getParameters());
                    }
                    result = AsyncTypes.toCompletionStageIfRequired(result, returnType);
                    return result;
                } catch (Throwable e) {
                    if (e instanceof InvocationTargetException) {
                        e = e.getCause();
                    }
                    if (e instanceof Exception) {
                        throw (Exception) e;
                    }
                    throw new FaultToleranceException("Error during fallback method invocation", e);
                }
            };
        } else {
            FallbackHandler<V> fallbackHandler = fallbackHandlerProvider.get(operation);
            if (fallbackHandler != null) {
                fallbackFunction = ctx -> {
                    InvocationContext interceptionContext = ctx.invocationContext.get(InvocationContext.class);
                    ExecutionContextWithInvocationContext executionContext = new ExecutionContextWithInvocationContext(
                            interceptionContext);
                    executionContext.setFailure(ctx.failure);
                    V result = fallbackHandler.handle(executionContext);
                    result = AsyncTypes.toCompletionStageIfRequired(result, returnType);
                    return result;
                };
            } else {
                throw new FaultToleranceException("Could not obtain fallback handler for " + point);
            }
        }

        if ((operation.isAsync() || operation.isAdditionalAsync()) && AsyncTypes.isKnown(returnType)
                && operation.isThreadOffloadRequired()) {
            fallbackFunction = new AsyncFallbackFunction(fallbackFunction, asyncExecutor);
        }

        return fallbackFunction;
    }

    private long getTimeInMs(GenericConfig<?> config, String configKey, String unitConfigKey) {
        long time = config.get(configKey);
        ChronoUnit unit = config.get(unitConfigKey);
        return Duration.of(time, unit).toMillis();
    }

    private SetOfThrowables getSetOfThrowables(GenericConfig<?> config, String configKey) {
        Class<? extends Throwable>[] throwableClasses = config.get(configKey);
        if (throwableClasses == null) {
            return SetOfThrowables.EMPTY;
        }
        return SetOfThrowables.create(Arrays.asList(throwableClasses));
    }

    private MetricsRecorder getMetricsRecorder(FaultToleranceOperation operation, InterceptionPoint point) {
        return cache.getMetrics(point, () -> metricsProvider.create(operation));
    }
}
