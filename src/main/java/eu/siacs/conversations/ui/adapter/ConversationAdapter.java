package eu.siacs.conversations.ui.adapter;

import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Optional;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemConversationBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Presence;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import eu.siacs.conversations.xmpp.manager.JingleManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConversationAdapter
        extends RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM   = 1;

    // Saturated, visually distinct colors for server stripes
    private static final int[] SERVER_STRIPE_COLORS = {
        0xFF2196F3, // blue
        0xFF4CAF50, // green
        0xFFF44336, // red
        0xFFFF9800, // orange
        0xFF9C27B0, // purple
        0xFF00BCD4, // cyan
        0xFFFF5722, // deep orange
        0xFF8BC34A, // light green
        0xFF3F51B5, // indigo
        0xFFE91E63, // pink
        0xFF009688, // teal
        0xFFFFEB3B, // yellow
    };
    private final Map<String, Integer> serverColorMap = new HashMap<>();

    private Account.State getServerState(final String server) {
        Account.State worst = Account.State.OFFLINE;
        for (final Conversation c : conversations) {
            if (server.equals(c.getAccount().getServer())) {
                final Account.State s = c.getAccount().getStatus();
                if (s == Account.State.ONLINE) return s;
                worst = s;
            }
        }
        return worst;
    }

    private android.graphics.drawable.Drawable buildStatusDrawable(final int fillColor) {
        final android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
        border.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        border.setColor(0xFFFFFFFF);
        final android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        fill.setColor(fillColor);
        final android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{border, fill});
        final int inset = Math.round(1.5f * activity.getResources().getDisplayMetrics().density);
        layer.setLayerInset(1, inset, inset, inset, inset);
        return layer;
    }

    private int getServerColor(final String server) {
        if (!serverColorMap.containsKey(server)) {
            serverColorMap.put(server, SERVER_STRIPE_COLORS[serverColorMap.size() % SERVER_STRIPE_COLORS.length]);
        }
        return serverColorMap.get(server);
    }

    /** Returns background color: on dark theme blends server color toward black,
     *  on light theme uses low-alpha tint over surface */
    private int getServerBackgroundColor(final android.content.Context ctx, final int stripeColor) {
        final int uiMode = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        final boolean isDark = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (isDark) {
            // Blend server color with black: 20% server color, 80% black
            final float ratio = 0.20f;
            final int r = Math.round(Color.red(stripeColor) * ratio);
            final int g = Math.round(Color.green(stripeColor) * ratio);
            final int b = Math.round(Color.blue(stripeColor) * ratio);
            return Color.argb(0xFF, r, g, b);
        } else {
            // Light theme: 10% opacity tint
            return Color.argb(0x1A, Color.red(stripeColor), Color.green(stripeColor), Color.blue(stripeColor));
        }
    }

    private final XmppActivity activity;
    private final List<Conversation> conversations;
    private OnConversationClickListener listener;

    // Flat list mixing headers (String = server) and items (Conversation)
    private final List<Object> items = new ArrayList<>();

    private void rebuildItems() {
        items.clear();
        String lastServer = null;
        for (final Conversation c : conversations) {
            final String server = c.getAccount().getServer();
            if (!server.equals(lastServer)) {
                items.add(server); // header
                lastServer = server;
            }
            items.add(c);
        }
    }

    public ConversationAdapter(XmppActivity activity, List<Conversation> conversations) {
        this.activity = activity;
        this.conversations = conversations;
        rebuildItems();
    }

    public void notifyDataSetChangedWithHeaders() {
        rebuildItems();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_server_header, parent, false);
            return new ConversationViewHolder(view);
        }
        return new ConversationViewHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(parent.getContext()),
                        R.layout.item_conversation,
                        parent,
                        false));
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder viewHolder, int position) {
        final Object item = items.get(position);

        // --- Header ---
        if (item instanceof String server) {
            final int stripeColor = getServerColor(server);
            final android.widget.TextView tv = viewHolder.itemView.findViewById(R.id.header_server_name);
            final View stripe = viewHolder.itemView.findViewById(R.id.header_stripe);
            final View statusDot = viewHolder.itemView.findViewById(R.id.header_server_status);
            if (tv != null) tv.setText(server);
            if (stripe != null) stripe.setBackgroundColor(stripeColor);
            viewHolder.itemView.setBackgroundColor(
                    getServerBackgroundColor(activity, stripeColor));
            // Server status indicator — colors match AccountAdapter logic
            if (statusDot != null) {
                final Account.State state = getServerState(server);
                final int dotColor;
                switch (state) {
                    case ONLINE:
                        dotColor = MaterialColors.getColor(
                                statusDot,
                                androidx.appcompat.R.attr.colorPrimary);
                        break;
                    case DISABLED:
                    case LOGGED_OUT:
                    case CONNECTING:
                        dotColor = MaterialColors.getColor(
                                statusDot,
                                com.google.android.material.R.attr.colorOnSurfaceVariant);
                        break;
                    default:
                        dotColor = MaterialColors.getColor(
                                statusDot,
                                androidx.appcompat.R.attr.colorError);
                        break;
                }
                statusDot.setBackground(buildStatusDrawable(dotColor));
            }
            return;
        }

        // --- Conversation item ---
        Conversation conversation = (Conversation) item;
        if (viewHolder.binding == null) return;

        // Remove top padding if this item follows a header
        final int px8 = Math.round(8 * activity.getResources().getDisplayMetrics().density);
        final int px0 = 0;
        final int pos = viewHolder.getAbsoluteAdapterPosition();
        final boolean afterHeader = pos > 0 && items.get(pos - 1) instanceof String;
        viewHolder.itemView.setPaddingRelative(
                viewHolder.itemView.getPaddingStart(),
                afterHeader ? px0 : px8,
                viewHolder.itemView.getPaddingEnd(),
                viewHolder.itemView.getPaddingBottom());
        CharSequence name = conversation.getName();
        if (name instanceof Jid) {
            viewHolder.binding.conversationName.setText(
                    IrregularUnicodeDetector.style(activity, (Jid) name));
        } else {
            viewHolder.binding.conversationName.setText(name);
        }

        if (conversation == ConversationFragment.getConversation(activity)) {
            viewHolder.binding.frame.setBackgroundResource(
                    R.drawable.background_selected_item_conversation);
            viewHolder.binding.serverStripe.setBackgroundColor(Color.TRANSPARENT);
        } else {
            final String server = conversation.getAccount().getServer();
            final int stripeColor = getServerColor(server);
            // Полупрозрачный фон, адаптированный под тему
            viewHolder.binding.frame.setBackgroundColor(
                    getServerBackgroundColor(activity, stripeColor));
            // Насыщенная полоса слева
            viewHolder.binding.serverStripe.setBackgroundColor(stripeColor);
        }

        // Show server domain label
        viewHolder.binding.serverName.setText(conversation.getAccount().getServer());
        viewHolder.binding.serverName.setVisibility(View.VISIBLE);

        final Message message = conversation.getLatestMessage();
        final int status = message.getStatus();
        final int unreadCount = conversation.unreadCount();
        final boolean isRead = conversation.isRead();
        final @DrawableRes Integer messageStatusDrawable =
                MessageAdapter.getMessageStatusAsDrawable(message, status);
        if (message.getType() == Message.TYPE_RTP_SESSION) {
            viewHolder.binding.messageStatus.setVisibility(View.GONE);
        } else if (messageStatusDrawable == null) {
            if (status <= Message.STATUS_RECEIVED) {
                viewHolder.binding.messageStatus.setVisibility(View.GONE);
            } else {
                viewHolder.binding.messageStatus.setVisibility(View.INVISIBLE);
            }
        } else {
            viewHolder.binding.messageStatus.setImageResource(messageStatusDrawable);
            if (status == Message.STATUS_SEND_DISPLAYED) {
                viewHolder.binding.messageStatus.setImageResource(R.drawable.ic_done_all_bold_24dp);
                ImageViewCompat.setImageTintList(
                        viewHolder.binding.messageStatus,
                        ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        viewHolder.binding.messageStatus,
                                        androidx.appcompat.R.attr.colorPrimary)));
            } else {
                ImageViewCompat.setImageTintList(
                        viewHolder.binding.messageStatus,
                        ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        viewHolder.binding.messageStatus,
                                        androidx.appcompat.R.attr.colorControlNormal)));
            }
            viewHolder.binding.messageStatus.setVisibility(View.VISIBLE);
        }
        final Conversation.Draft draft = isRead ? conversation.getDraft() : null;
        if (unreadCount > 0) {
            viewHolder.binding.unreadCount.setVisibility(View.VISIBLE);
            viewHolder.binding.unreadCount.setUnreadCount(unreadCount);
        } else {
            viewHolder.binding.unreadCount.setVisibility(View.GONE);
        }

        if (isRead) {
            viewHolder.binding.conversationName.setTypeface(null, Typeface.NORMAL);
        } else {
            viewHolder.binding.conversationName.setTypeface(null, Typeface.BOLD);
        }

        // Color name green if contact is online, show indicator dot
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            final Presence.Availability availability =
                    conversation.getContact().getShownStatus();
            if (availability != Presence.Availability.OFFLINE) {
                final int uiMode = activity.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                final boolean isDark = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                // Dark theme: emerald green; light theme: dark green
                viewHolder.binding.conversationName.setTextColor(
                        isDark ? 0xFF50C878 : 0xFF1B5E20);
                viewHolder.binding.onlineIndicator.setVisibility(View.VISIBLE);
            } else {
                viewHolder.binding.conversationName.setTextColor(
                        MaterialColors.getColor(
                                viewHolder.binding.conversationName,
                                com.google.android.material.R.attr.colorOnSurface));
                viewHolder.binding.onlineIndicator.setVisibility(View.GONE);
            }
        } else {
            viewHolder.binding.conversationName.setTextColor(
                    MaterialColors.getColor(
                            viewHolder.binding.conversationName,
                            com.google.android.material.R.attr.colorOnSurface));
            viewHolder.binding.onlineIndicator.setVisibility(View.GONE);
        }

        if (draft != null) {
            viewHolder.binding.conversationLastmsgImg.setVisibility(View.GONE);
            viewHolder.binding.conversationLastmsg.setText(draft.getMessage());
            viewHolder.binding.senderName.setText(R.string.draft);
            viewHolder.binding.senderName.setVisibility(View.VISIBLE);
            viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.NORMAL);
            viewHolder.binding.senderName.setTypeface(null, Typeface.ITALIC);
        } else {
            final boolean fileAvailable = !message.isDeleted();
            final boolean showPreviewText;
            if (fileAvailable
                    && (message.isFileOrImage()
                            || message.treatAsDownloadable()
                            || message.isGeoUri())) {
                final var attachment = Attachment.of(message);
                final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
                showPreviewText = false;
                viewHolder.binding.conversationLastmsgImg.setImageResource(imageResource);
                viewHolder.binding.conversationLastmsgImg.setVisibility(View.VISIBLE);
            } else {
                viewHolder.binding.conversationLastmsgImg.setVisibility(View.GONE);
                showPreviewText = true;
            }
            final Pair<CharSequence, Boolean> preview =
                    UIHelper.getMessagePreview(
                            activity,
                            message,
                            viewHolder.binding.conversationLastmsg.getCurrentTextColor());
            if (showPreviewText) {
                viewHolder.binding.conversationLastmsg.setText(UIHelper.shorten(preview.first));
            } else {
                viewHolder.binding.conversationLastmsgImg.setContentDescription(preview.first);
            }
            viewHolder.binding.conversationLastmsg.setVisibility(
                    showPreviewText ? View.VISIBLE : View.GONE);
            if (preview.second) {
                if (isRead) {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.ITALIC);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.NORMAL);
                } else {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.BOLD_ITALIC);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.BOLD);
                }
            } else {
                if (isRead) {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.NORMAL);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.NORMAL);
                } else {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.BOLD);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.BOLD);
                }
            }
            if (status == Message.STATUS_RECEIVED) {
                if (conversation.getMode() == Conversation.MODE_MULTI) {
                    viewHolder.binding.senderName.setVisibility(View.VISIBLE);
                    final var displayName = UIHelper.getMessageDisplayName(message);
                    final var displayNameParts = displayName.split("\\s+");
                    // Skip when nickname only consists of blank chars
                    if (displayNameParts.length == 0) {
                        viewHolder.binding.senderName.setText(String.format("%s:", displayName));
                    } else {
                        viewHolder.binding.senderName.setText(
                                String.format("%s:", displayNameParts[0]));
                    }
                } else {
                    viewHolder.binding.senderName.setVisibility(View.GONE);
                }
            } else if (message.getType() != Message.TYPE_STATUS) {
                viewHolder.binding.senderName.setVisibility(View.VISIBLE);
                viewHolder.binding.senderName.setText(
                        String.format("%s:", activity.getString(R.string.me)));
            } else {
                viewHolder.binding.senderName.setVisibility(View.GONE);
            }
        }

        final Optional<OngoingRtpSession> ongoingCall;
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ongoingCall = Optional.absent();
        } else {
            final var manager =
                    conversation.getAccount().getXmppConnection().getManager(JingleManager.class);
            ongoingCall = manager.getOngoingRtpConnection(conversation.getContact());
        }

        if (ongoingCall.isPresent()) {
            viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
            viewHolder.binding.notificationStatus.setImageResource(
                    R.drawable.ic_phone_in_talk_24dp);
        } else {
            final long muted_till =
                    conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
            if (muted_till == Long.MAX_VALUE) {
                viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
                viewHolder.binding.notificationStatus.setImageResource(
                        R.drawable.ic_notifications_off_24dp);
            } else if (muted_till >= System.currentTimeMillis()) {
                viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
                viewHolder.binding.notificationStatus.setImageResource(
                        R.drawable.ic_notifications_paused_24dp);
            } else if (conversation.alwaysNotify()) {
                viewHolder.binding.notificationStatus.setVisibility(View.GONE);
            } else {
                viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
                viewHolder.binding.notificationStatus.setImageResource(
                        R.drawable.ic_notifications_none_24dp);
            }
        }

        long timestamp;
        if (draft != null) {
            timestamp = draft.getTimestamp();
        } else {
            timestamp = conversation.getLatestMessage().getTimeSent();
        }
        viewHolder.binding.pinnedOnTop.setVisibility(
                conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)
                        ? View.VISIBLE
                        : View.GONE);
        viewHolder.binding.conversationLastupdate.setText(
                UIHelper.readableTimeDifference(activity, timestamp));
        AvatarWorkerTask.loadAvatar(
                conversation,
                viewHolder.binding.conversationImage,
                R.dimen.avatar_on_conversation_overview);

        // Apply avatar shape from settings
        final boolean circleAvatars = new AppSettings(activity).isCircleAvatars();
        viewHolder.binding.conversationImage.setShapeAppearanceModel(
                viewHolder.binding.conversationImage
                        .getShapeAppearanceModel()
                        .toBuilder()
                        .setAllCornerSizes(circleAvatars
                                ? new com.google.android.material.shape.RelativeCornerSize(0.5f)
                                : new com.google.android.material.shape.AbsoluteCornerSize(8))
                        .build());

        viewHolder.itemView.setOnClickListener(v -> listener.onConversationClick(v, conversation));
    }

    public Object getItem(final int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public int getItemCount() {
        return items.size();
    }

    public void setConversationClickListener(OnConversationClickListener listener) {
        this.listener = listener;
    }

    public void insert(Conversation c, int position) {
        conversations.add(position, c);
        notifyDataSetChanged();
    }

    public void remove(Conversation conversation, int position) {
        conversations.remove(conversation);
        notifyItemRemoved(position);
    }

    public interface OnConversationClickListener {
        void onConversationClick(View view, Conversation conversation);
    }

    public static class ConversationViewHolder extends RecyclerView.ViewHolder {
        public final ItemConversationBinding binding;

        // For conversation items
        private ConversationViewHolder(final ItemConversationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        // For server header items
        private ConversationViewHolder(final View view) {
            super(view);
            this.binding = null;
        }
    }
}
