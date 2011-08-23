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
package com.xpn.xwiki.plugin.lucene;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.document.Field;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.PropertyInterface;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.ListItem;
import com.xpn.xwiki.objects.classes.PasswordClass;
import com.xpn.xwiki.objects.classes.StaticListClass;

/**
 * Hold the property values of the XWiki.ArticleClass Objects.
 */
public class ObjectData extends IndexData
{
    private static final Log LOG = LogFactory.getLog(ObjectData.class);

    public ObjectData(final XWikiDocument doc, final XWikiContext context)
    {
        super(doc, context);
        setAuthor(doc.getAuthor());
        setCreator(doc.getCreator());
        setModificationDate(doc.getDate());
        setCreationDate(doc.getCreationDate());
    }

    /**
     * @see com.xpn.xwiki.plugin.lucene.IndexData#getType()
     */
    @Override
    public String getType()
    {
        return LucenePlugin.DOCTYPE_OBJECTS;
    }

    @Override
    public String getId()
    {
        return new StringBuffer(super.getId()).append(".objects").toString();
    }

    /**
     * @return a string containing the result of {@link IndexData#getFullText(XWikiDocument,XWikiContext)}plus the full
     *         text content (values of title,category,content and extract ) XWiki.ArticleClass Object, as far as it
     *         could be extracted.
     */
    @Override
    public String getFullText(XWikiDocument doc, XWikiContext context)
    {
        StringBuffer retval = new StringBuffer(super.getFullText(doc, context));
        String contentText = getContentAsText(doc, context);
        if (contentText != null) {
            retval.append(" ").append(contentText);
        }
        return retval.toString();
    }

    /**
     * @return string containing value of title,category,content and extract of XWiki.ArticleClass
     */
    private String getContentAsText(XWikiDocument doc, XWikiContext context)
    {
        StringBuffer contentText = new StringBuffer();

        contentText.append(doc.getTitle());
        contentText.append(" ");
        contentText.append(doc.getContent());
        contentText.append(" ");

        try {
            LOG.info(doc.getFullName());
            for (String className : doc.getxWikiObjects().keySet()) {
                for (BaseObject obj : doc.getObjects(className)) {
                    extractContent(contentText, obj, context);
                }
            }
        } catch (Exception e) {
            LOG.error("error getting content from  XWiki Objects ", e);
            e.printStackTrace();
        }
        return contentText.toString();
    }

    private void extractContent(StringBuffer contentText, BaseObject baseObject, XWikiContext context)
    {
        try {
            if (baseObject != null) {
                Object[] propertyNames = baseObject.getPropertyNames();
                for (int i = 0; i < propertyNames.length; i++) {
                    BaseProperty baseProperty = (BaseProperty) baseObject.getField((String) propertyNames[i]);
                    if ((baseProperty != null) && (baseProperty.getValue() != null)) {
                        PropertyInterface prop = baseObject.getxWikiClass(context).getField((String) propertyNames[i]);
                        if (!(prop instanceof PasswordClass)) {
                            contentText.append(baseProperty.getValue().toString());
                        }
                    }
                    contentText.append(" ");
                }
            }
        } catch (Exception e) {
            LOG.error("error getting content from  XWiki Object ", e);
            e.printStackTrace();
        }
    }

    @Override
    public void addDataToLuceneDocument(org.apache.lucene.document.Document luceneDoc, XWikiDocument doc,
        XWikiContext context)
    {
        super.addDataToLuceneDocument(luceneDoc, doc, context);
        for (String className : doc.getxWikiObjects().keySet()) {
            for (BaseObject obj : doc.getObjects(className)) {
                if (obj != null) {
                    luceneDoc.add(new Field(IndexFields.OBJECT, obj.getClassName(), Field.Store.YES,
                        Field.Index.TOKENIZED));
                    Object[] propertyNames = obj.getPropertyNames();
                    for (int i = 0; i < propertyNames.length; i++) {
                        try {
                            indexProperty(luceneDoc, obj, (String) propertyNames[i], context);
                        } catch (Exception e) {
                            LOG.error("error extracting fulltext for document " + this, e);
                        }
                    }
                }
            }
        }
    }

    private void indexProperty(org.apache.lucene.document.Document luceneDoc, BaseObject baseObject,
        String propertyName, XWikiContext context)
    {
        String fieldFullName = baseObject.getClassName() + "." + propertyName;
        BaseClass bClass = baseObject.getxWikiClass(context);
        PropertyInterface prop = bClass.getField(propertyName);

        if (prop instanceof PasswordClass) {
            // Do not index passwords
        } else if (prop instanceof StaticListClass && ((StaticListClass) prop).isMultiSelect()) {
            indexStaticList(luceneDoc, baseObject, (StaticListClass) prop, propertyName, context);
        } else {
            final String ft = getContentAsText(baseObject, propertyName, context);
            if (ft != null) {
                luceneDoc.add(new Field(fieldFullName, ft, Field.Store.YES, Field.Index.TOKENIZED));
                luceneDoc.add(new Field(fieldFullName + IndexFields.UNTOKENIZED, ft.toUpperCase(), Field.Store.NO, Field.Index.UN_TOKENIZED));
            }
        }
    }

    private void indexStaticList(org.apache.lucene.document.Document luceneDoc, BaseObject baseObject,
        StaticListClass prop, String propertyName, XWikiContext context)
    {
        Map possibleValues = prop.getMap(context);
        List keys = baseObject.getListValue(propertyName);
        String fieldFullName = baseObject.getClassName() + "." + propertyName;
        Iterator it = keys.iterator();
        while (it.hasNext()) {
            String value = (String) it.next();
            ListItem item = (ListItem) possibleValues.get(value);
            if (item != null) {
                // we index the key of the list
                String fieldName = fieldFullName + ".key";
                luceneDoc.add(new Field(fieldName, item.getId(), Field.Store.YES, Field.Index.TOKENIZED));
                luceneDoc.add(new Field(fieldName + IndexFields.UNTOKENIZED, item.getId().toUpperCase(), Field.Store.NO, Field.Index.UN_TOKENIZED));

                // we index the value
                fieldName = fieldFullName + ".value";
                luceneDoc.add(new Field(fieldName, item.getValue(), Field.Store.YES, Field.Index.TOKENIZED));
                luceneDoc.add(new Field(fieldName + IndexFields.UNTOKENIZED, item.getValue().toUpperCase(), Field.Store.NO, Field.Index.UN_TOKENIZED));
                if (!item.getId().equals(item.getValue())) {
                    luceneDoc.add(new Field(fieldFullName, item.getValue(), Field.Store.YES, Field.Index.TOKENIZED));
                    luceneDoc.add(new Field(fieldFullName + IndexFields.UNTOKENIZED, item.getValue().toUpperCase(), Field.Store.NO, Field.Index.UN_TOKENIZED));
                }
            }

            // we index both if value is not equal to the id(key)
            luceneDoc.add(new Field(fieldFullName, value, Field.Store.YES, Field.Index.TOKENIZED));
            luceneDoc.add(new Field(fieldFullName + IndexFields.UNTOKENIZED, value.toUpperCase(), Field.Store.NO, Field.Index.UN_TOKENIZED));
        }
    }

    public String getFullText(XWikiDocument doc, BaseObject baseObject, String property, XWikiContext context)
    {
        return getContentAsText(baseObject, property, context);
    }

    private String getContentAsText(BaseObject baseObject, String property, XWikiContext context)
    {
        StringBuffer contentText = new StringBuffer();
        try {
            BaseProperty baseProperty;
            baseProperty = (BaseProperty) baseObject.getField(property);
            if (baseProperty.getValue() != null) {
                PropertyInterface prop = baseObject.getxWikiClass(context).getField(property);
                if (!(prop instanceof PasswordClass)) {
                    contentText.append(baseProperty.getValue().toString());
                }
            }
        } catch (Exception e) {
            LOG.error("error getting content from  XWiki Objects ", e);
            e.printStackTrace();
        }
        return contentText.toString();
    }
}
