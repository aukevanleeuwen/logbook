package org.zalando.logbook;

import org.apiguardian.api.API;

import javax.annotation.Nullable;

import static org.apiguardian.api.API.Status.MAINTAINED;
import static org.apiguardian.api.API.Status.STABLE;
import static org.zalando.logbook.DefaultFilters.defaultValues;

@API(status = STABLE)
public final class ResponseFilters {

    private ResponseFilters() {

    }

    @API(status = MAINTAINED)
    public static ResponseFilter defaultValue() {
        return defaultValues(ResponseFilter.class).stream()
                .reduce(replaceBody(BodyReplacers.defaultValue()), ResponseFilter::merge);
    }

    public static ResponseFilter replaceBody(final BodyReplacer<HttpResponse> replacer) {
        return response -> {
            @Nullable final String replacement = replacer.replace(response);
            if (replacement == null) {
                return response;
            }
            return new BodyReplacementHttpResponse(response.withoutBody(), replacement);
        };
    }

}
