package com.sdl.dxa.modelservice.service.processing.expansion;

import com.sdl.dxa.api.datamodel.model.ContentModelData;
import com.sdl.dxa.api.datamodel.model.EntityModelData;
import com.sdl.dxa.api.datamodel.model.KeywordModelData;
import com.sdl.dxa.api.datamodel.model.PageModelData;
import com.sdl.dxa.api.datamodel.model.RichTextData;
import com.sdl.dxa.api.datamodel.model.util.ListWrapper;
import com.sdl.dxa.api.datamodel.processing.DataModelDeepFirstSearcher;
import com.sdl.dxa.common.dto.EntityRequestDto;
import com.sdl.dxa.common.dto.PageRequestDto;
import com.sdl.dxa.modelservice.service.ConfigService;
import com.sdl.dxa.modelservice.service.EntityModelService;
import com.sdl.dxa.modelservice.service.EntityModelServiceSuppressLinks;
import com.sdl.dxa.tridion.linking.RichTextLinkResolver;
import com.sdl.dxa.tridion.linking.api.BatchLinkResolver;
import com.sdl.dxa.tridion.linking.api.descriptors.SingleLinkDescriptor;
import com.sdl.dxa.tridion.linking.descriptors.ComponentLinkDescriptor;
import com.sdl.dxa.tridion.linking.descriptors.DynamicComponentLinkDescriptor;
import com.sdl.dxa.tridion.linking.descriptors.RichTextLinkDescriptor;
import com.sdl.dxa.tridion.linking.processors.EntityLinkProcessor;
import com.sdl.dxa.tridion.linking.processors.EntryLinkProcessor;
import com.sdl.dxa.tridion.linking.processors.FragmentLinkListProcessor;
import com.sdl.dxa.tridion.linking.processors.FragmentListProcessor;
import com.sdl.webapp.common.api.content.ContentProviderException;
import com.sdl.webapp.common.util.TcmUtils;
import com.tridion.meta.NameValuePair;
import com.tridion.taxonomies.Keyword;
import com.tridion.taxonomies.TaxonomyFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sdl.web.util.ContentServiceQueryConstants.LINK_TYPE_BINARY;
import static com.sdl.web.util.ContentServiceQueryConstants.LINK_TYPE_COMPONENT;

/**
 * Expands {@link PageModelData} using an instance of {@link PageRequestDto}.
 */
@Slf4j
public class PageModelExpander extends DataModelDeepFirstSearcher {

    private PageRequestDto pageRequest;

    private EntityModelService entityModelService;

    private RichTextLinkResolver richTextLinkResolver;

    private BatchLinkResolver batchLinkResolver;

    private ConfigService configService;

    private Integer pageId;

    public PageModelExpander(PageRequestDto pageRequest,
                             EntityModelService entityModelService,
                             RichTextLinkResolver richTextLinkResolver,
                             ConfigService configService,
                             BatchLinkResolver batchLinkResolver,
                             Integer pageId) {
        this.pageRequest = pageRequest;
        this.entityModelService = entityModelService;
        this.richTextLinkResolver = richTextLinkResolver;
        this.configService = configService;
        this.batchLinkResolver = batchLinkResolver;
        this.pageId = pageId;
    }

    /**
     * Expands a data model.
     *
     * @param page model to expand
     */
    public void expandPage(@Nullable PageModelData page) {
        long startTime = System.currentTimeMillis();
        log.info("Expansion of the page with id {} has started!", page.getId());

        traverseObject(page);

        // Resolve all links and update the model after page has been traversed and expanded
        this.batchLinkResolver.resolveAndFlush(new HashSet<>());

        log.info("Expansion of the page with id {} has taken {} ms.", page.getId(),
                System.currentTimeMillis() - startTime);
    }

    @Override
    protected boolean goingDeepIsAllowed() {
        return pageRequest.getDepthCounter().depthIncreaseAndCheckIfSafe();
    }

    @Override
    protected void goLevelUp() {
        pageRequest.getDepthCounter().depthDecrease();
    }

    @Override
    protected void processPageModel(PageModelData pageModelData) {

        // Note: we get the page ID here in case we're inside an include page.
        int pageId = NumberUtils.toInt(pageModelData.getId(), -1);

        Map<String, String> meta = pageModelData.getMeta();
        Set<String> notResolvedLinks = new HashSet<>();
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            String entryValue = entry.getValue();
            if (TcmUtils.isTcmUri(entryValue)) {
                Integer pubId = TcmUtils.getPublicationId(entryValue);
                Integer componentId = TcmUtils.getItemId(entryValue);

                SingleLinkDescriptor ld = new ComponentLinkDescriptor(pubId, pageId, componentId, new EntryLinkProcessor(meta,
                        entry.getKey()), LINK_TYPE_BINARY);
                this.batchLinkResolver.dispatchLinkResolution(ld);
            } else {
                List<String> links = this.richTextLinkResolver.retrieveAllLinksFromFragment(entryValue);
                this.batchLinkResolver.dispatchMultipleLinksResolution(
                        new RichTextLinkDescriptor(
                                pageRequest.getPublicationId(),
                                pageId,
                                links,
                                new FragmentLinkListProcessor(
                                        meta,
                                        entry.getKey(),
                                        entry.getValue(),
                                        this.richTextLinkResolver
                                )
                        ),
                        notResolvedLinks);

            }
        }
    }

    @Override
    protected void processEntityModel(EntityModelData entityModelData) {
        if (_isEntityToExpand(entityModelData)) {
            _expandEntity(entityModelData, pageRequest);
        }

        SingleLinkDescriptor ld;
        if (entityModelData.getId().matches("\\d+-\\d+")) {
            ld = new DynamicComponentLinkDescriptor(
                    pageRequest.getPublicationId(),
                    this.pageId,
                    new EntityLinkProcessor(entityModelData));
        } else {
            ld = new ComponentLinkDescriptor(
                    pageRequest.getPublicationId(),
                    this.pageId,
                    Integer.parseInt(entityModelData.getId()),
                    new EntityLinkProcessor(entityModelData),
                    LINK_TYPE_COMPONENT);
        }

        //Odata -> BatchLinkResolverImpl
        //In-process -> TridionBatchLinkResolver
        this.batchLinkResolver.dispatchLinkResolution(ld);
    }

    @Override
    protected void processKeywordModel(KeywordModelData keywordModel) {
        if (!_isKeywordToExpand(keywordModel)) {
            return;
        }

        String keywordURI =
                TcmUtils.buildKeywordTcmUri(String.valueOf(pageRequest.getPublicationId()), keywordModel.getId());
        log.trace("Found keyword to expand, uri = '{}'", keywordURI);
        Keyword keyword = new TaxonomyFactory().getTaxonomyKeyword(keywordURI);

        if (keyword != null) {
            keywordModel.setDescription(keyword.getKeywordDescription()).setKey(keyword.getKeywordKey())
                    .setTitle(keyword.getKeywordName())
                    .setTaxonomyId(String.valueOf(TcmUtils.getItemId(keyword.getTaxonomyURI())))
                    .setMetadata(_getMetadata(keyword, pageRequest));
        } else {
            _suppressIfNeeded("Keyword " + keywordModel.getId() + " in publication " + pageRequest.getPublicationId() +
                    " cannot be found, is it published?", configService.getErrors().isMissingKeywordSuppress());
        }
    }

    @Override
    protected void processRichTextData(RichTextData richTextData) {

        long start = System.currentTimeMillis();

        final List<Object> fragments = richTextData.getFragments();

        log.debug("Processing {} fragments.", fragments.size());

        richTextData.setFragments(fragments);

        List<String> links = new ArrayList<>();
        Set<String> notResolvedLinks = new HashSet<>();
        for (Object fragment : fragments) {
            if (fragment instanceof String) {
                String fragmentString = (String) fragment;
                links.addAll(this.richTextLinkResolver
                        .retrieveAllLinksFromFragment(fragmentString));
            }
        }
        this.batchLinkResolver.dispatchMultipleLinksResolution(
                new RichTextLinkDescriptor(
                        pageRequest.getPublicationId(),
                        this.pageId,
                        links,
                        new FragmentListProcessor(richTextData,
                                this.richTextLinkResolver)), notResolvedLinks);

        log.debug("Page Model RTF resolving took: {} ms.", ((System.currentTimeMillis() - start)));
    }

    @NotNull
    private ContentModelData _getMetadata(Keyword keyword, PageRequestDto pageRequest) {
        ContentModelData metadata = new ContentModelData();
        for (Map.Entry<String, NameValuePair> entry : keyword.getKeywordMeta().getNameValues().entrySet()) {
            String key = entry.getKey();
            NameValuePair value = entry.getValue();

            metadata.put(key, _getMetadataValues(pageRequest, value));
        }
        return metadata;
    }


    @NotNull
    private ListWrapper<?> _getMetadataValues(PageRequestDto pageRequest, NameValuePair value) {
        ListWrapper<?> values = null;

        String firstValue = String.valueOf(value.getFirstValue());
        if (TcmUtils.isTcmUri(firstValue)) {
            int itemType = TcmUtils.getItemType(firstValue);
            if (itemType == TcmUtils.KEYWORD_ITEM_TYPE) {
                List<KeywordModelData> keywords = new ArrayList<>();
                for (Object uri : value.getMultipleValues()) {
                    KeywordModelData keywordModelData =
                            new KeywordModelData().setId(String.valueOf(TcmUtils.getItemId(String.valueOf(uri))));
                    traverseObject(keywordModelData);
                    keywords.add(keywordModelData);
                }
                values = new ListWrapper.KeywordModelDataListWrapper(keywords);
            } else if (itemType == TcmUtils.COMPONENT_ITEM_TYPE) {
                List<EntityModelData> entities = new ArrayList<>();
                for (Object uri : value.getMultipleValues()) {
                    String id = String.valueOf(TcmUtils.getItemId(String.valueOf(uri))) + "-" +
                            configService.getDefaults().getDynamicTemplateId(pageRequest.getPublicationId());
                    EntityModelData entityModelData = EntityModelData.builder().id(id).build();
                    traverseObject(entityModelData);
                    entities.add(entityModelData);
                }
                values = new ListWrapper.EntityModelDataListWrapper(entities);
            }
        }

        values = values == null ? new ListWrapper<>(value.getMultipleValues()) : values;
        return values;
    }

    private boolean _isKeywordToExpand(Object value) {
        return value instanceof KeywordModelData && ((KeywordModelData) value).getTitle() == null;
    }

    private boolean _isEntityToExpand(Object value) {
        return value instanceof EntityModelData && ((EntityModelData) value).getSchemaId() == null &&
                ((EntityModelData) value).getId().matches("\\d+-\\d+");
    }

    private void _expandEntity(EntityModelData toExpand, PageRequestDto pageRequest) {

        EntityRequestDto entityRequest = EntityRequestDto.builder(
                pageRequest.getPublicationId(), toExpand.getId(), this.pageId).build();

        log.trace("Found entity to expand {}, request {}", toExpand.getId(), entityRequest);
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Loading of the entity with id {} has started.", entityRequest.getComponentId());

            EntityModelData e;
            if (EntityModelServiceSuppressLinks.class.isAssignableFrom(entityModelService.getClass())) {
                e = ((EntityModelServiceSuppressLinks) entityModelService).loadEntity(entityRequest, false);
            } else {
                e = entityModelService.loadEntity(entityRequest);
            }
            log.debug("Loading of the entity with id {} has taken {} ms", entityRequest.getComponentId(),
                    System.currentTimeMillis() - startTime);
            toExpand.copyFrom(e);
        } catch (ContentProviderException e) {
            _suppressIfNeeded("Cannot expand entity " + toExpand + " for page " + pageRequest,
                    configService.getErrors().isMissingEntitySuppress(), e);
        }
    }

    private void _suppressIfNeeded(String message, boolean suppressingFlag) {
        log.warn(message);
        if (!suppressingFlag) {
            throw new DataModelExpansionException(message);
        }
    }

    private void _suppressIfNeeded(String message, boolean suppressingFlag, ContentProviderException e) {
        log.warn(message, e);
        if (!suppressingFlag) {
            throw new DataModelExpansionException(message, e);
        }
    }
}
