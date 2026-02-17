package org.zalando.logbook.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.zalando.logbook.Logbook;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyEnumeration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class LogbookFilterTest {

    @Test
    void shouldCreateLogbookFilter() {
        new LogbookFilter();
    }

    @Test
    void shouldCreateSecureLogbookFilter() {
        new SecureLogbookFilter();
    }

    @Test
    void shouldCallInit() {
        new LogbookFilter().init(mock(FilterConfig.class));
    }

    @Test
    void shouldCallDestroy() {
        new LogbookFilter().destroy();
    }

    @Test
    void shouldHandleIOExceptionOnFlushBufferAndWriteResponse() throws Exception {
        Logbook logbook = mock(Logbook.class);
        Logbook.RequestWritingStage requestWritingStage = mock(Logbook.RequestWritingStage.class);
        Logbook.ResponseWritingStage responseWritingStage = mock(Logbook.ResponseWritingStage.class);
        LogbookFilter filter = new LogbookFilter(logbook);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(logbook.process(any())).thenReturn(requestWritingStage);
        when(requestWritingStage.write()).thenReturn(requestWritingStage);
        when(requestWritingStage.process(any())).thenReturn(responseWritingStage);
        when(request.getHeaderNames()).thenReturn(emptyEnumeration());
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getAttribute(any())).thenReturn(new AtomicBoolean(false));

        doThrow(new IOException("Simulated IOException")).when(response).flushBuffer();

        filter.doFilter(request, response, chain);

        verify(responseWritingStage).write();
    }

    @Test
    void shouldNotThrowNPEIfRequestDoesntContainWritingStageSynchronizationBoolean() throws Exception {
        Logbook logbook = mock(Logbook.class);
        Logbook.RequestWritingStage requestWritingStage = mock(Logbook.RequestWritingStage.class);
        Logbook.ResponseWritingStage responseWritingStage = mock(Logbook.ResponseWritingStage.class);
        LogbookFilter filter = new LogbookFilter(logbook);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);


        when(logbook.process(any())).thenReturn(requestWritingStage);
        when(requestWritingStage.write()).thenReturn(requestWritingStage);
        when(requestWritingStage.process(any())).thenReturn(responseWritingStage);
        when(request.getHeaderNames()).thenReturn(emptyEnumeration());
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getAttribute(captor.capture())).thenReturn(null);

        doThrow(new IOException("Simulated IOException")).when(response).flushBuffer();

        filter.doFilter(request, response, chain);

        verify(responseWritingStage, never()).write();
        assertThat(captor.getValue()).contains("-Synchronization-");
    }

    @Test
    void shouldNotWriteResponsesTwiceEvenIfOnCompleteHandlerIsCalledMultipleTimes() throws Exception {
        // Please read: https://github.com/zalando/logbook/discussions/2248
        // I cannot reproduce the scenario with a regular integration test, but to be on the safe
        // side, we're simulating a duplicate call to the #write method in LogbookFilter.
        Logbook logbook = mock(Logbook.class);
        Logbook.RequestWritingStage requestWritingStage = mock(Logbook.RequestWritingStage.class);
        Logbook.ResponseWritingStage responseWritingStage = mock(Logbook.ResponseWritingStage.class);
        LogbookFilter filter = new LogbookFilter(logbook);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        AtomicBoolean synchronization = new AtomicBoolean(false);
        AsyncContext asyncContext = mock(AsyncContext.class);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);


        when(logbook.process(any())).thenReturn(requestWritingStage);
        when(requestWritingStage.write()).thenReturn(requestWritingStage);
        when(requestWritingStage.process(any())).thenReturn(responseWritingStage);
        when(request.getHeaderNames()).thenReturn(emptyEnumeration());
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getAttribute(any())).thenAnswer(i -> {
                    String name = i.getArgument(0);
                    if (name.contains(Logbook.ResponseProcessingStage.class.getName())) {
                        return requestWritingStage;
                    } else if (name.contains("-Synchronization-")) {
                        return synchronization;
                    }
                    return null;
                }
        );
        // pretend async started and capture the registered onComplete listener
        when(request.isAsyncStarted()).thenReturn(true);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        doNothing().when(asyncContext).addListener(listenerCaptor.capture());

        // REQUEST dispatch
        filter.doFilter(request, response, chain);
        // ASYNC dispatch
        when(request.getDispatcherType()).thenReturn(DispatcherType.ASYNC);
        filter.doFilter(request, response, chain);

        // Pretend - for some reason - two async onComplete calls
        AsyncListener registeredAsyncListener = listenerCaptor.getValue();
        registeredAsyncListener.onComplete(null);
        registeredAsyncListener.onComplete(null);

        verify(responseWritingStage, times(1)).write();
    }

}
