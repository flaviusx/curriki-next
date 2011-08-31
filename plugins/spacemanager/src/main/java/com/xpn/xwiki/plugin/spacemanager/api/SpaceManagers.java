package com.xpn.xwiki.plugin.spacemanager.api;

import java.util.HashMap;
import java.util.Map;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/** 
 * @version $Id$
 */
public class SpaceManagers
{
    protected static Map spacemanagers = new HashMap();

    public static void addSpaceManager(SpaceManager sm)
    {
        spacemanagers.put(sm.getSpaceTypeName(), sm);
    }

    public static SpaceManager findSpaceManagerForSpace(String space, XWikiContext context)
        throws SpaceManagerException
    {
        XWikiDocument doc;
        try {
            doc = context.getWiki().getDocument(space, "WebPreferences", context);
        } catch (XWikiException e) {
            throw new SpaceManagerException(e);
        }
        String type = doc.getStringValue(SpaceManager.SPACE_CLASS_NAME, "type");
        if (type == null)
            type = SpaceManager.SPACE_DEFAULT_TYPE;
        return findSpaceManagerForType(type);
    }

    public static SpaceManager findSpaceManagerForType(String type)
    {
        return (SpaceManager) spacemanagers.get(type);
    }
}
