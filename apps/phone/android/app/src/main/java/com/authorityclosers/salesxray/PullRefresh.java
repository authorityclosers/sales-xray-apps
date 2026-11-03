package com.authorityclosers.salesxray;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.WebView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * Pull to refresh around the page (native feel, 3 Nov 2026), the way Hotwire Native does it: on each touchstart the
 * page tells the app whether a pull may start there (sx-phone.js: a list screen, at the very top, outside sheets,
 * dialogs, fields and anything scrolled down). Until it has said yes for this touch, the touch only scrolls the page,
 * so a form or a sheet never reloads under the seller's finger.
 *
 * A pull asks the page to refresh its own data (its "sx-refresh" event); a page that doesn't take it loads again.
 */
@SuppressLint("ViewConstructor")
final class PullRefresh extends SwipeRefreshLayout {

    /** The spinner never stays longer than this (a page that never says it is done, or a slow network). */
    private static final long MAX_SPIN_MS = 10_000;

    private final WebView page;
    private final Runnable stop = this::done;
    private boolean touchMayPull;
    private boolean down;
    private final int startOffset;
    private final int endOffset;

    /** Puts the pull layout where the page was, with the page inside it. Before the first layout. */
    static PullRefresh around(WebView page) {
        ViewGroup parent = (ViewGroup) page.getParent();
        int index = parent.indexOfChild(page);
        ViewGroup.LayoutParams params = page.getLayoutParams();
        parent.removeViewAt(index);
        PullRefresh pull = new PullRefresh(page.getContext(), page);
        pull.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(pull, index, params);
        return pull;
    }

    private PullRefresh(Context context, WebView page) {
        super(context);
        this.page = page;
        setColorSchemeResources(R.color.sx_pull_arrow);
        setProgressBackgroundColorSchemeResource(R.color.sx_pull_circle);
        startOffset = getProgressViewStartOffset();
        endOffset = getProgressViewEndOffset();
        setOnRefreshListener(this::refresh);
    }

    @Override
    public boolean canChildScrollUp() {
        // While the finger goes down the layout only notes where (SwipeRefreshLayout drops a touch whose start it
        // didn't see); after that the page decides.
        if (down) return page.getScrollY() > 0;
        return !touchMayPull || page.getScrollY() > 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return super.onInterceptTouchEvent(event);
        touchMayPull = false;
        down = true;
        try {
            return super.onInterceptTouchEvent(event);
        } finally {
            down = false;
        }
    }

    /** The page's answer for the touch that just started. Main thread. */
    void touch(boolean mayPull) {
        touchMayPull = mayPull;
    }

    /** The page refreshed its data, or a new page has painted: the spinner goes. Main thread. */
    void done() {
        removeCallbacks(stop);
        if (isRefreshing()) setRefreshing(false);
    }

    /** The page draws under the status bar (SafeArea): the spinner comes down below it. */
    void setTopInset(int pixels) {
        setProgressViewOffset(false, startOffset + pixels, endOffset + pixels);
    }

    private void refresh() {
        removeCallbacks(stop);
        postDelayed(stop, MAX_SPIN_MS);
        page.evaluateJavascript("window.__sxPhone&&window.__sxPhone.pullRefresh?window.__sxPhone.pullRefresh():null", result -> {
            // "page": the page refreshes and says when; "reload": it loads again, and the next paint ends the spinner.
            if (!"\"page\"".equals(result) && !"\"reload\"".equals(result)) page.reload();
        });
    }
}
