package mattw.youtube.commentsuite.db;

import mattw.youtube.datav3.entrypoints.ChannelsList;
import mattw.youtube.datav3.entrypoints.CommentsList;
import org.apache.commons.text.StringEscapeUtils;

/**
 * @author mattwright324
 */
public class YouTubeChannel extends YouTubeObject {

    public YouTubeChannel(ChannelsList.Item item) {
        this(item, false);
    }

    public YouTubeChannel(ChannelsList.Item item, boolean fetchThumb) {
        super(item.getId(), StringEscapeUtils.unescapeHtml4(item.snippet.title), item.snippet.thumbnails.getDefault().getURL().toString(), fetchThumb);
        setTypeId(YType.CHANNEL);
    }

    public YouTubeChannel(String channelId, String name, String thumbUrl, boolean fetchThumb) {
        super(channelId, name, thumbUrl, fetchThumb);
        setTypeId(YType.CHANNEL);
    }

    public YouTubeChannel(CommentsList.Item item) {
        this(item, false);
    }

    public YouTubeChannel(CommentsList.Item item, boolean fetchThumb) {
        this(item.snippet.authorChannelId.value, StringEscapeUtils.unescapeHtml4(item.snippet.authorDisplayName), item.snippet.authorProfileImageUrl, fetchThumb);
        setTypeId(YType.CHANNEL);
    }

    public String toString() { return getTitle(); }
}
