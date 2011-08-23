/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.curriki.plugin.activitystream.impl;

import java.util.ArrayList;
import java.util.List;

import org.apache.velocity.VelocityContext;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Document;
import com.xpn.xwiki.api.XWiki;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.notify.XWikiDocChangeNotificationInterface;
import com.xpn.xwiki.notify.XWikiNotificationRule;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.plugin.activitystream.api.ActivityEvent;
import com.xpn.xwiki.plugin.activitystream.api.ActivityEventPriority;
import com.xpn.xwiki.plugin.activitystream.api.ActivityEventType;
import com.xpn.xwiki.plugin.activitystream.api.ActivityStreamException;
import com.xpn.xwiki.plugin.activitystream.impl.ActivityEventImpl;
import com.xpn.xwiki.plugin.activitystream.impl.ActivityStreamImpl;
import com.xpn.xwiki.plugin.mailsender.Mail;
import com.xpn.xwiki.plugin.mailsender.MailSenderPlugin;
import com.xpn.xwiki.plugin.spacemanager.api.Space;
import com.xpn.xwiki.plugin.spacemanager.api.SpaceManager;
import com.xpn.xwiki.plugin.spacemanager.api.SpaceManagers;
import com.xpn.xwiki.plugin.spacemanager.api.SpaceUserProfile;
import com.xpn.xwiki.render.XWikiVelocityRenderer;

public class CurrikiActivityStream extends ActivityStreamImpl
{
    private static final String CURRIKI_SPACE_TYPE = "currikispace";

    private static final String SPACE_CLASS_NAME = "XWiki.SpaceClass";

    public static final String DOCUMENTATION_FILE = "documentation-file";

    public static final String DOCUMENTATION_WIKI = "documentation-wiki";

    public CurrikiActivityStream()
    {
        super();
    }

    /**
     * {@inheritDoc}
     */
    public void notify(XWikiNotificationRule rule, XWikiDocument newdoc, XWikiDocument olddoc,
        int event, XWikiContext context)
    {
        try {
            String spaceName = newdoc.getSpace();
            if (spaceName == null) {
                return;
            }
            if (spaceName.startsWith("Messages_Group_")) {
                handleMessageEvent(newdoc, olddoc, event, context);
            } else if (spaceName.startsWith("Documentation_Group_")) {
                handleDocumentationEvent(newdoc, olddoc, event, context);
            } else if (spaceName.startsWith("Coll_Group_")) {
                handleResourceEvent(newdoc, olddoc, event, context);
            } else if (spaceName.startsWith("UserProfiles_Group_")) {
                handleMemberEvent(newdoc, olddoc, event, context);
            }
            // TODO handle events from MemberGroup, AdminGroup and Role_<roleName>Group
        } catch (Throwable t) {
            // Error in activity stream notify should be ignored but logged in the log file
            t.printStackTrace();
        }
    }

    protected void handleMessageEvent(XWikiDocument newdoc, XWikiDocument olddoc, int event,
        XWikiContext context)
    {
        String streamName = getStreamName(newdoc.getSpace(), context);
        if (streamName == null) {
            return;
        }

        BaseObject article = newdoc.getObject("XWiki.ArticleClass");
        if (article == null) {
            if (olddoc == null) {
                return;
            }
            article = olddoc.getObject("XWiki.ArticleClass");
            if (article == null) {
                return;
            }
            event = XWikiDocChangeNotificationInterface.EVENT_DELETE;
        } else {
            double version = Double.parseDouble(newdoc.getVersion());
            double initialVersion = 4.1;
            if ((olddoc != null && olddoc.getObject("XWiki.ArticleClass") == null)
                || (olddoc == null && version == initialVersion)) {
                event = XWikiDocChangeNotificationInterface.EVENT_NEW;
            } else if (version < initialVersion) {
                return;
            }
        }

        boolean notify = false;
        String level = "message";
        if ("commentadd".equals(context.getAction())) {
            event = XWikiDocChangeNotificationInterface.EVENT_NEW;
            level = "comment";
            notify = true;
        }

        List params = new ArrayList();
        params.add(article.getStringValue("title"));
        params.add(getUserName(context.getUser(), context));
        params.add(level);

        try {
            switch (event) {
                case XWikiDocChangeNotificationInterface.EVENT_NEW:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.CREATE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_CHANGE:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.UPDATE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    notify = true;
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_DELETE:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.DELETE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    break;
            }
            if (notify) {
                sendUpdateNotification(newdoc.getSpace().substring("Messages_".length()), newdoc,
                    context);
            }
        } catch (Throwable e) {
            // Error in activity stream notify should be ignored but logged in the log file
            e.printStackTrace();
        }
    }

    protected void handleDocumentationEvent(XWikiDocument newdoc, XWikiDocument olddoc,
        int event, XWikiContext context)
    {
        String streamName = getStreamName(newdoc.getSpace(), context);
        if (streamName == null) {
            return;
        }

        String docTitle = newdoc.getTitle();
        BaseObject tag = newdoc.getObject("XWiki.TagClass");
        if (tag == null) {
            if (olddoc == null) {
                return;
            }
            tag = olddoc.getObject("XWiki.TagClass");
            if (tag == null) {
                return;
            }
            docTitle = olddoc.getTitle();
            event = XWikiDocChangeNotificationInterface.EVENT_DELETE;
        } else {
            double version = Double.parseDouble(newdoc.getVersion());
            double initialVersion = 4.1;
            if (tag.getStringValue("tags").contains(DOCUMENTATION_WIKI)) {
                initialVersion = 3.1;
            }
            if ((olddoc != null && olddoc.getObject("XWiki.TagClass") == null)
                || (olddoc == null && version == initialVersion)) {
                event = XWikiDocChangeNotificationInterface.EVENT_NEW;
            } else if (version < initialVersion) {
                return;
            }
        }

        String docType = tag.getStringValue("tags");
        if (docType.contains(DOCUMENTATION_FILE)) {
            docType = DOCUMENTATION_FILE;
        } else if (docType.contains(DOCUMENTATION_WIKI)) {
            docType = DOCUMENTATION_WIKI;
        } else {
            return;
        }

        List params = new ArrayList();
        params.add(docTitle);
        params.add(getUserName(context.getUser(), context));
        params.add(docType);

        try {
            switch (event) {
                case XWikiDocChangeNotificationInterface.EVENT_NEW:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.CREATE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_CHANGE:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.UPDATE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    sendUpdateNotification(
                        newdoc.getSpace().substring("Documentation_".length()), newdoc, context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_DELETE:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.DELETE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    break;
            }
        } catch (Throwable e) {
            // Error in activity stream notify should be ignored but logged in the log file
            e.printStackTrace();
        }
    }

    protected void handleResourceEvent(XWikiDocument newdoc, XWikiDocument olddoc, int event,
        XWikiContext context)
    {
        String streamName = getStreamName(newdoc.getSpace(), context);
        if (streamName == null) {
            return;
        }

        BaseObject asset = newdoc.getObject("CurrikiCode.AssetClass");
        if (asset == null) {
            if (olddoc == null) {
                return;
            }
            asset = olddoc.getObject("CurrikiCode.AssetClass");
            if (asset == null) {
                return;
            }
            event = XWikiDocChangeNotificationInterface.EVENT_DELETE;
        } else {
            double version = Double.parseDouble(newdoc.getVersion());
            if (version==2.1) {
                event = XWikiDocChangeNotificationInterface.EVENT_NEW;
            } else if (!newdoc.getVersion().endsWith(".1")||version==1.1) {
                // we ignore minor version edits and the first version
                return;
            }


        }

        List params = new ArrayList();
        params.add(newdoc.getTitle());
        params.add(getUserName(context.getUser(), context));

        try {
            switch (event) {
                case XWikiDocChangeNotificationInterface.EVENT_NEW:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.CREATE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_CHANGE:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.UPDATE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    sendUpdateNotification(newdoc.getSpace().substring("Coll_".length()), newdoc,
                        context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_DELETE:
                    addDocumentActivityEvent(streamName, newdoc, ActivityEventType.DELETE,
                        ActivityEventPriority.NOTIFICATION, "", params, context);
                    break;
            }
        } catch (Throwable e) {
            // Error in activity stream notify should be ignored but logged in the log file
            e.printStackTrace();
        }
    }

    protected void handleMemberEvent(XWikiDocument newdoc, XWikiDocument olddoc, int event,
        XWikiContext context)
    {
        String streamName = getStreamName(newdoc.getSpace(), context);
        if (streamName == null) {
            return;
        }

        BaseObject profile = newdoc.getObject("XWiki.SpaceUserProfileClass");
        if (profile == null) {
            if (olddoc == null) {
                return;
            }
            profile = olddoc.getObject("XWiki.SpaceUserProfileClass");
            if (profile == null) {
                return;
            }
            event = XWikiDocChangeNotificationInterface.EVENT_DELETE;
        } else {
            double version = Double.parseDouble(newdoc.getVersion());
            double initialVersion = 2.1;
            if ((olddoc != null && olddoc.getObject("XWiki.SpaceUserProfileClass") == null)
                || (olddoc == null && version == initialVersion)) {
                event = XWikiDocChangeNotificationInterface.EVENT_NEW;
            } else if (version < initialVersion) {
                return;
            }
        }

        try {
            String profileName = profile.getName();
            String user = "XWiki." + profileName.substring(profileName.indexOf(".") + 1);
            XWikiDocument userDoc = context.getWiki().getDocument(user, context);

            List params = new ArrayList();
            params.add(user);
            params.add(getUserName(user, context));

            ActivityEvent activityEvent = new ActivityEventImpl();
            activityEvent.setStream(streamName);
            activityEvent.setPriority(ActivityEventPriority.NOTIFICATION);
            activityEvent.setUrl(userDoc.getExternalURL("view", context));
            activityEvent.setParams(params);
            activityEvent.setDate(newdoc.getDate());

            switch (event) {
                case XWikiDocChangeNotificationInterface.EVENT_NEW:
                    activityEvent.setType(ActivityEventType.CREATE);
                    addActivityEvent(activityEvent, newdoc, context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_CHANGE:
                    activityEvent.setType(ActivityEventType.UPDATE);
                    addActivityEvent(activityEvent, newdoc, context);
                    break;
                case XWikiDocChangeNotificationInterface.EVENT_DELETE:
                    // ignore
                    break;
            }
        } catch (Throwable e) {
            // Error in activity stream notify should be ignored but logged in the log file
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getStreamName(String space, XWikiContext context)
    {
        XWikiDocument doc;
        try {
            doc = context.getWiki().getDocument(space, "WebPreferences", context);
            String type = doc.getStringValue(SPACE_CLASS_NAME, "type");

            if (CURRIKI_SPACE_TYPE.equals(type))
                return space;

            String parentSpace = space.substring(space.indexOf("_") + 1);
            doc = context.getWiki().getDocument(parentSpace, "WebPreferences", context);
            type = doc.getStringValue(SPACE_CLASS_NAME, "type");
            if (CURRIKI_SPACE_TYPE.equals(type))
                return parentSpace;

            // could not find a Curriki space
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getUserName(String user, XWikiContext context)
    {
        String userName = null;
        try {
            XWikiDocument userDoc = context.getWiki().getDocument(user, context);
            if (!userDoc.isNew()) {
                userName =
                    (userDoc.getStringValue("XWiki.XWikiUsers", "first_name") + " " + userDoc
                        .getStringValue("XWiki.XWikiUsers", "last_name")).trim();
            }
        } catch (XWikiException e) {
        }
        if (userName == null) {
            userName = user.substring(user.indexOf(".") + 1);
        }
        return userName;
    }

    /**
     * Sends a notification email to the creator of the given document if it has been updated by
     * another user and he opted in his space profile for this kind of notifications.
     * 
     * @param spaceName the space the changed document is associated with. Also, the document
     *            creator's profile in this space can block this notification
     * @param doc the changed document
     * @param context the XWiki context
     * @throws Exception
     */
    private void sendUpdateNotification(String spaceName, XWikiDocument doc, XWikiContext context)
        throws Exception
    {
        if (doc.getCreator().equals(context.getUser())) {
            return;
        }
        SpaceManager spaceManager = SpaceManagers.findSpaceManagerForSpace(spaceName, context);
        SpaceUserProfile profile =
            spaceManager.getSpaceUserProfile(spaceName, doc.getCreator(), context);
        if (!profile.getAllowNotificationsFromSelf()) {
            return;
        }

        Space space = spaceManager.getSpace(spaceName, context);
        String templateDocFullName = "Groups.MailTemplateUpdateNotification";
        XWikiDocument mailDoc = context.getWiki().getDocument(templateDocFullName, context);
        XWikiDocument translatedMailDoc = mailDoc.getTranslatedDocument(context);

        VelocityContext vContext = new VelocityContext();
        vContext.put("space", space);
        vContext.put("udoc", new Document(doc, context));
        vContext.put("xwiki", new XWiki(context.getWiki(), context));
        vContext.put("context", new com.xpn.xwiki.api.Context(context));

        String mailFrom = context.getWiki().getXWikiPreference("admin_email", context);
        String mailTo =
            context.getWiki().getDocument(doc.getCreator(), context).getStringValue(
                "XWiki.XWikiUsers", "email");
        String mailSubject =
            XWikiVelocityRenderer.evaluate(translatedMailDoc.getTitle(), templateDocFullName,
                vContext, context);
        String mailContent =
            XWikiVelocityRenderer.evaluate(translatedMailDoc.getContent(), templateDocFullName,
                vContext, context);

        MailSenderPlugin mailSender =
            (MailSenderPlugin) context.getWiki().getPlugin("mailsender", context);
        mailSender.prepareVelocityContext(mailFrom, mailTo, "", "", vContext, context);
        Mail mail = new Mail(mailFrom, mailTo, null, null, mailSubject, mailContent, null);
        mailSender.sendMail(mail, context);
    }

    /* Override searchEvents to change group by behavior for filter (give create items intead of update items)
     */
    public List<ActivityEvent> searchEvents(String hql, boolean filter, int nb, int start, XWikiContext context) throws ActivityStreamException
    {
        String searchHql;

        if (filter) {
            searchHql =
                "select act from ActivityEventImpl as act, ActivityEventImpl as act2 where act.eventId=act2.eventId and "
                    + hql
                    + " group by act.requestId having (act.priority)=max(act2.priority) and (act.type)=min(act.type) order by act.date desc";
        } else {
            searchHql =
                "select act from ActivityEventImpl as act where " + hql
                    + " order by act.date desc";
        }

        try {
            return context.getWiki().search(searchHql, nb, start, context);
        } catch (XWikiException e) {
            throw new ActivityStreamException(e);
        }
    }

}
