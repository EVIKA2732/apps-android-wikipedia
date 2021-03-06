package org.wikipedia.page;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;

import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory;
import org.wikipedia.dataclient.page.PageClient;
import org.wikipedia.dataclient.page.PageClientFactory;
import org.wikipedia.dataclient.page.PageLead;
import org.wikipedia.dataclient.page.PageRemaining;
import org.wikipedia.html.ImageTagParser;
import org.wikipedia.html.PixelDensityDescriptorParser;
import org.wikipedia.savedpages.PageImageUrlParser;
import org.wikipedia.util.log.L;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Response;

import static org.wikipedia.util.DimenUtil.calculateLeadImageWidth;
import static org.wikipedia.util.UriUtil.resolveProtocolRelativeUrl;

final class PageCacher {

    @SuppressLint("CheckResult")
    static void loadIntoCache(@NonNull PageTitle title) {
        L.d("Loading page into cache: " + title.getPrefixedText());
        PageImageUrlParser parser = new PageImageUrlParser(new ImageTagParser(), new PixelDensityDescriptorParser());
        PageClient client = PageClientFactory.create(title.getWikiSite(), title.namespace());
        Observable.zip(leadReq(client, title), remainingReq(client, title), (leadRsp, sectionsRsp) -> {
            Set<String> imageUrls = new HashSet<>();
            if (leadRsp.body() != null) {
                imageUrls.addAll(parser.parse(leadRsp.body()));
            }
            if (sectionsRsp.body() != null) {
                imageUrls.addAll(parser.parse(sectionsRsp.body()));
            }
            return imageUrls;
        }).subscribeOn(Schedulers.io())
                .subscribe(imageUrls -> fetchImages(title.getWikiSite(), imageUrls), L::e);
    }

    @NonNull
    private static Observable<Response<PageLead>> leadReq(@NonNull PageClient client, @NonNull PageTitle title) {
        return client.lead(title.getWikiSite(), null, null, null, title.getPrefixedText(),
                calculateLeadImageWidth());
    }

    @NonNull
    private static Observable<Response<PageRemaining>> remainingReq(@NonNull PageClient client,
                                                    @NonNull PageTitle title) {
        return client.sections(title.getWikiSite(), null, null, title.getPrefixedText());
    }

    private static void fetchImages(@NonNull final WikiSite wiki,
                                    @NonNull final Iterable<String> urls) {
        OkHttpClient client = OkHttpConnectionFactory.getClient();
        for (String url : urls) {
            url = resolveProtocolRelativeUrl(wiki, url);
            Request request = new Request.Builder().url(url).build();
            client.newCall(request).enqueue(new ImageCallback());
        }
    }

    private static class ImageCallback implements okhttp3.Callback {
        @Override
        public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
            L.e(e);
        }

        @Override
        public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response)
                throws IOException {
            // Note: raw non-Retrofit usage of OkHttp Requests requires that the Response body is
            // read for the cache to be written.
            if (response.body() != null) {
                // noinspection ConstantConditions
                response.body().close();
            }
        }
    }

    private PageCacher() { }
}
