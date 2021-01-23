package io.mattw.youtube.commentsuite.refresh;

import io.mattw.youtube.commentsuite.Cleanable;
import io.mattw.youtube.commentsuite.FXMLSuite;
import io.mattw.youtube.commentsuite.db.*;
import io.mattw.youtube.commentsuite.util.ElapsedTime;
import io.mattw.youtube.commentsuite.util.StringTuple;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ProgressBar;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static javafx.application.Platform.runLater;

public class GroupRefresh extends Thread implements RefreshInterface {

    private static final Logger logger = LogManager.getLogger();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("hh:mm a");

    private final DoubleProperty progressProperty = new SimpleDoubleProperty(0.0);
    private final BooleanProperty endedProperty = new SimpleBooleanProperty(false);
    private final StringProperty elapsedTimeProperty = new SimpleStringProperty();
    private final StringProperty statusStepProperty = new SimpleStringProperty();
    private final ObservableList<String> errorList = FXCollections.observableArrayList();

    private final LongProperty newVideosProperty = new SimpleLongProperty(0);
    private final LongProperty totalVideosProperty = new SimpleLongProperty(0);
    private final LongProperty newCommentsProperty = new SimpleLongProperty(0);
    private final LongProperty totalCommentsProperty = new SimpleLongProperty(0);
    private final LongProperty newViewersProperty = new SimpleLongProperty(0);
    private final LongProperty totalViewersProperty = new SimpleLongProperty(0);

    private final Group group;
    private final RefreshOptions options;
    private final CommentDatabase database;

    private final VideoIdProducer videoIdProducer;
    private final UniqueVideoIdProducer uniqueVideoIdProducer;
    private final VideoProducer videoProducer;
    private final CommentThreadProducer commentThreadProducer;
    private final ReplyProducer replyProducer;
    private final ChannelProducer channelProducer;
    private final CommentConsumer commentConsumer;
    private final ChannelConsumer channelConsumer;

    private boolean hardShutdown = false;
    private boolean endedOnError = false;

    public GroupRefresh(Group group, RefreshOptions options) {
        this.group = group;
        this.options = options;
        this.database = FXMLSuite.getDatabase();

        this.videoIdProducer = new VideoIdProducer();
        this.uniqueVideoIdProducer = new UniqueVideoIdProducer();
        this.videoProducer = new VideoProducer(options);
        this.commentThreadProducer = new CommentThreadProducer(options);
        this.replyProducer = new ReplyProducer(options);
        this.channelProducer = new ChannelProducer();
        this.commentConsumer = new CommentConsumer();
        this.channelConsumer = new ChannelConsumer();
    }

    @Override
    public void run() {
        logger.debug("Starting NewGroupRefresh options={}", options);
        runLater(() -> progressProperty.setValue(ProgressBar.INDETERMINATE_PROGRESS));
        startElapsedTimer();

        videoIdProducer.produceTo(uniqueVideoIdProducer, String.class);
        videoIdProducer.accept(database.getGroupItems(group));
        videoIdProducer.setMessageFunc(this::postMessage);

        uniqueVideoIdProducer.produceTo(videoProducer, String.class);
        uniqueVideoIdProducer.setMessageFunc(this::postMessage);

        videoProducer.produceTo(commentThreadProducer, YouTubeVideo.class);
        videoProducer.setMessageFunc(this::postMessage);

        try {
            // Parse GroupItems to VideoIds
            runLater(() -> statusStepProperty.setValue("Grabbing Videos"));
            videoIdProducer.startProducing();
            await(videoIdProducer, "Await videoIdProducer over");
            database.commit();

            uniqueVideoIdProducer.startProducing();
            await(uniqueVideoIdProducer, "Await uniqueVideoIdProducer over");

            // Parse VideoIds to Videos
            runLater(() -> statusStepProperty.setValue("Grabbing Video Data"));
            videoProducer.startProducing();
            await(videoProducer, "Await videoProducer over");
        } catch (SQLException | InterruptedException e) {
            postMessage(Level.FATAL, e, null);
        }

        commentThreadProducer.produceTo(commentConsumer, YouTubeComment.class);
        commentThreadProducer.produceTo(channelProducer, String.class);
        commentThreadProducer.produceTo(replyProducer, StringTuple.class);
        commentThreadProducer.setMessageFunc(this::postMessage);

        replyProducer.produceTo(commentConsumer, YouTubeComment.class);
        replyProducer.produceTo(channelConsumer, YouTubeChannel.class);
        replyProducer.keepAliveWith(commentThreadProducer);
        replyProducer.setStartProduceOnFirstAccept(true);
        replyProducer.setMessageFunc(this::postMessage);

        channelProducer.produceTo(channelConsumer, YouTubeChannel.class);
        channelProducer.keepAliveWith(commentThreadProducer);
        channelProducer.setStartProduceOnFirstAccept(true);
        channelProducer.setMessageFunc(this::postMessage);

        commentConsumer.keepAliveWith(commentThreadProducer, replyProducer);
        commentConsumer.setStartProduceOnFirstAccept(true);
        commentConsumer.setMessageFunc(this::postMessage);

        channelConsumer.keepAliveWith(commentThreadProducer, channelProducer, replyProducer);
        channelConsumer.setStartProduceOnFirstAccept(true);
        channelConsumer.setMessageFunc(this::postMessage);

        try {
            // Parse Videos to CommentThreads, Replies, Channels
            runLater(() -> statusStepProperty.setValue("Grabbing Comments"));
            startProgressThread();
            commentThreadProducer.startProducing();

            await(commentThreadProducer, "Await commentThreadProducer over");
            await(replyProducer, "Await replyProducer over");
            await(channelProducer, "Await channelProducer over");
            await(commentConsumer, "Await commentConsumer over");
            await(channelConsumer, "Await channelConsumer over");
        } catch (InterruptedException e) {
            postMessage(Level.FATAL, e, null);
        }

        try {
            database.commit();

            awaitMillis(100);

            runLater(() -> statusStepProperty.setValue("Done"));
        } catch (SQLException e) {
            postMessage(Level.FATAL, e, "Failed to commit");
        }

        runLater(() -> {
            endedProperty.setValue(true);
            pollProcessed();
        });

        logger.debug("Ending NewGroupRefresh videoSkipped={} duplicateChannelIdSkipped={}",
                videoProducer.getTimeframeSkipped(),
                channelProducer.getDuplicateSkipped());
    }

    private void await(ConsumerMultiProducer<?> consumer, String message) throws InterruptedException {
        if (consumer.getExecutorGroup().isStillWorking()) {
            consumer.getExecutorGroup().await();
            consumer.onCompletion();

            logger.debug(message);
        }

        logger.debug(consumer);
    }

    private void postMessage(final Level level, final Throwable error, final String message) {
        logger.debug("Refresh {} - {}", level,  message);

        final String time = formatter.format(LocalDateTime.now());

        if (level == Level.FATAL) {
            endedOnError = true;
            hardShutdown();
        }

        if (message != null) {
            runLater(() -> errorList.add(0, String.format("%s - %s", time, message)));
        }

        if (error != null) {
            runLater(() -> errorList.add(0, String.format("%s - %s", time, error.getLocalizedMessage())));
        }
    }

    private void startProgressThread() {
        final ExecutorService es = Executors.newSingleThreadExecutor();
        es.submit(() -> {
            logger.debug("Starting Progress Thread");

            final List<ConsumerMultiProducer<?>> trackProgress = Arrays.asList(commentThreadProducer, replyProducer, channelProducer, commentConsumer, channelConsumer);
            commentThreadProducer.getProgressWeight().set(100);
            while (!endedProperty.getValue()) {
                double totalAccepted = 0d;
                double totalProcessed = 0d;

                for (ConsumerMultiProducer<?> consumer : trackProgress) {
                    final double weight = consumer.getProgressWeight().get();
                    totalAccepted += consumer.getTotalAccepted().get() * weight;
                    totalProcessed += consumer.getTotalProcessed().get() * weight;
                }

                final double percentage = totalProcessed / totalAccepted;
                runLater(() -> progressProperty.setValue(percentage));

                awaitMillis(100);
            }
            logger.debug("Ended Progress Thread");
        });
        es.shutdown();
    }

    private void pollProcessed() {
        newVideosProperty.setValue(uniqueVideoIdProducer.getNewVideos());
        totalVideosProperty.setValue(uniqueVideoIdProducer.getTotalVideos());

        newCommentsProperty.setValue(commentConsumer.getNewComments());
        totalCommentsProperty.setValue(commentConsumer.getTotalComments());

        newViewersProperty.setValue(channelConsumer.getNewChannels());
        totalViewersProperty.setValue(channelConsumer.getTotalChannels());
    }

    private void startElapsedTimer() {
        final ElapsedTime elapsedTimer = new ElapsedTime();
        final ExecutorService es = Executors.newSingleThreadExecutor();
        es.submit(() -> {
            logger.debug("Starting Elapsed Timer");

            while (!endedProperty.getValue()) {
                runLater(() -> {
                    elapsedTimeProperty.setValue(String.format("%s CtP-%s RP-%s ChP-%s CmC-%s ChC-%s",
                            elapsedTimer.humanReadableFormat(),
                            commentThreadProducer.getBlockingQueue().size(),
                            replyProducer.getBlockingQueue().size(),
                            channelProducer.getBlockingQueue().size(),
                            commentConsumer.getBlockingQueue().size(),
                            channelConsumer.getBlockingQueue().size()));
                    pollProcessed();
                });
                awaitMillis(27);
            }

            logger.debug("Ended Elapsed Timer");
        });
        es.shutdown();

        /* Debug thread to make sure no items remaining
        final ExecutorService tes = Executors.newSingleThreadExecutor();
        tes.submit(() -> {
            while (true) {
                logger.debug("replyProducer {} {} items={}", replyProducer.shouldKeepAlive(), replyProducer.getExecutorGroup().isStillWorking(), new ArrayList(replyProducer.getBlockingQueue()));
                logger.debug("channelProducer {} {} items={}", channelProducer.shouldKeepAlive(), channelProducer.getExecutorGroup().isStillWorking(), new ArrayList(channelProducer.getBlockingQueue()));
                logger.debug("commentConsumer {} {} items={}", commentConsumer.shouldKeepAlive(), commentConsumer.getExecutorGroup().isStillWorking(), new ArrayList(commentConsumer.getBlockingQueue()));
                logger.debug("channelConsumer {} {} items={}", channelConsumer.shouldKeepAlive(), channelConsumer.getExecutorGroup().isStillWorking(), new ArrayList(channelConsumer.getBlockingQueue()));
                awaitMillis(5000);
            }
        });
        tes.shutdown();
         */
    }

    private void awaitMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void appendError(String error) {

    }

    @Override
    public void hardShutdown() {
        videoIdProducer.setHardShutdown(true);
        uniqueVideoIdProducer.setHardShutdown(true);
        videoProducer.setHardShutdown(true);
        commentThreadProducer.setHardShutdown(true);
        replyProducer.setHardShutdown(true);
        channelProducer.setHardShutdown(true);
        commentConsumer.setHardShutdown(true);
        channelConsumer.setHardShutdown(true);

        hardShutdown = true;
    }

    @Override
    public LongProperty newVideosProperty() {
        return newVideosProperty;
    }

    @Override
    public LongProperty totalVideosProperty() {
        return totalVideosProperty;
    }

    @Override
    public LongProperty newCommentsProperty() {
        return newCommentsProperty;
    }

    @Override
    public LongProperty totalCommentsProperty() {
        return totalCommentsProperty;
    }

    @Override
    public LongProperty newViewersProperty() {
        return newViewersProperty;
    }

    @Override
    public LongProperty totalViewersProperty() {
        return totalViewersProperty;
    }

    @Override
    public BooleanProperty endedProperty() {
        return endedProperty;
    }

    @Override
    public DoubleProperty progressProperty() {
        return progressProperty;
    }

    @Override
    public StringProperty statusStepProperty() {
        return statusStepProperty;
    }

    @Override
    public StringProperty elapsedTimeProperty() {
        return elapsedTimeProperty;
    }

    @Override
    public ObservableList<String> getObservableErrorList() {
        return errorList;
    }

    @Override
    public Boolean isEndedOnError() {
        return endedOnError;
    }

    @Override
    public Boolean isHardShutdown() {
        return hardShutdown;
    }

}
