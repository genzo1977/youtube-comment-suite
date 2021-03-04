package io.mattw.youtube.commentsuite.refresh;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

import static org.apache.commons.lang3.builder.ToStringStyle.SIMPLE_STYLE;

public class RefreshOptions {

    private RefreshStyle style = RefreshStyle.MODERATE;
    private RefreshTimeframe timeframe = style.getTimeframe();
    private RefreshCommentPages commentPages = style.getCommentPages();
    private boolean commentPagesSmart = true;
    private RefreshCommentOrder commentOrder = style.getCommentOrder();
    private RefreshCommentPages replyPages = style.getReplyPages();
    private RefreshCommentPages reviewPages = RefreshCommentPages.ALL;

    public RefreshStyle getStyle() {
        return style;
    }

    public RefreshOptions setStyle(RefreshStyle style) {
        this.style = style;
        return this;
    }

    public RefreshTimeframe getTimeframe() {
        return timeframe;
    }

    public RefreshOptions setTimeframe(RefreshTimeframe timeframe) {
        this.timeframe = timeframe;
        return this;
    }

    public RefreshCommentPages getCommentPages() {
        return commentPages;
    }

    public RefreshOptions setCommentPages(RefreshCommentPages commentPages) {
        this.commentPages = commentPages;
        return this;
    }

    public boolean isCommentPagesSmart() {
        return commentPagesSmart;
    }

    public RefreshOptions setCommentPagesSmart(boolean commentPagesSmart) {
        this.commentPagesSmart = commentPagesSmart;
        return this;
    }

    public RefreshCommentOrder getCommentOrder() {
        return commentOrder;
    }

    public RefreshOptions setCommentOrder(RefreshCommentOrder commentOrder) {
        this.commentOrder = commentOrder;
        return this;
    }

    public RefreshCommentPages getReplyPages() {
        return replyPages;
    }

    public RefreshOptions setReplyPages(RefreshCommentPages replyPages) {
        this.replyPages = replyPages;
        return this;
    }

    public RefreshCommentPages getReviewPages() {
        return reviewPages;
    }

    public RefreshOptions setReviewPages(RefreshCommentPages reviewPages) {
        this.reviewPages = reviewPages;
        return this;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this, SIMPLE_STYLE);
    }
}
