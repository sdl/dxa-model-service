package com.sdl.dxa.tridion.linking.processors;

import com.sdl.dxa.api.datamodel.model.RichTextData;
import com.sdl.dxa.tridion.linking.api.processors.LinkListProcessor;
import com.sdl.dxa.tridion.linking.RichTextLinkResolver;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FragmentListProcessor implements LinkListProcessor {

    private RichTextLinkResolver resolver;

    private RichTextData model;

    private String uuid;

    private String fragment;

    public FragmentListProcessor(RichTextData model,
                                 ImmutablePair<String, String> uuidAndFragmentPair,
                                 RichTextLinkResolver resolver) {
        this.model = model;

        this.uuid = uuidAndFragmentPair.getLeft();
        this.fragment = uuidAndFragmentPair.getRight();

        this.resolver = resolver;
    }

    @Override
    public void update(Map<String, String> links) {
        Set<String> linksNotResolved = Collections.synchronizedSet(new HashSet<>());
        List<Object> fragmentList = this.model
                .getValues()
                .stream()
                .map(fragment -> {
                    if (fragment instanceof ImmutablePair && ((ImmutablePair) fragment).getLeft() == uuid) {
                        return resolver.applyBatchOfLinksStart(this.fragment, links, linksNotResolved);
                    }
                    return fragment;
                }).collect(Collectors.toList());

        this.model.setFragments(fragmentList);
    }
}