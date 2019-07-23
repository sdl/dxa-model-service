package com.sdl.dxa.tridion.linking.impl;

import com.google.common.base.Strings;
import com.sdl.dxa.modelservice.service.ConfigService;
import com.sdl.dxa.tridion.linking.RichTextLinkResolver;
import com.sdl.webapp.common.api.content.LinkResolver;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts a a String fragment and resolves possible links from it.
 */
@Component
@Slf4j
public class RichTextLinkResolverImpl implements RichTextLinkResolver {

    /**
     * Matches {@code xmlns:xlink} TDD and {@code xlink:} and namespace text fragment.
     */
    private static final Pattern XMLNS_FOR_REMOVAL =
            Pattern.compile("(?!<\\s)x(link:|mlns(\\s*=\\s*\"[^\"]*\"|:[^\"]*\"[^\"]*\"))",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern SPACES_FOR_REMOVAL =
            Pattern.compile("\\s+(\\s)|\\s+(>)",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern XLINK_XLMNS_FOR_GENERATING_HREF =
            Pattern.compile("(?<before><a[^>]*?)(?<prefix>\\sx(link|mlns):)(?<tag>href\\s*=\\s*)(?<value>\"[^\"]*?\")(?<after>[^>]*?>)",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern COLLECT_LINK =
            // <p>Text <a data="1" href="tcm:1-2" data2="2">link text</a><!--CompLink tcm:1-2--> after text</p>
            // tcmUri: tcm:1-2
            Pattern.compile("href=\"(?<tcmUri>tcm:\\d++-\\d++)\"",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern FULL_LINK =
            // <p>Text <a data="1" href="tcm:1-2" data2="2">link text</a><!--CompLink tcm:1-2--> after text</p>
            // beforeWithLink: <p>Text <a data="1" href=
            // before: <p>Text
            // tcmUri: tcm:1-2
            // afterWithLink: " data2="2">link text</a><!--CompLink tcm:1-2--> after text</p>
            // after: link text</a><!--CompLink tcm:1-2--> after text</p>
            //                                       <a           href= "           tcm:1    -3                       "      >          link2                </a>
            Pattern.compile("(?<openingTagStart><a[^>]*?\\s++href\\s*=\\s*\")(?<tcmUri>tcm:\\d++-\\d++)(?<openingTagEnd>\"[^>]*?>)(?<linkText>.*?)(?<closingTag></a>)(<!--CompLink\\s++\\2-->)?",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern START_LINK =
            // <p>Text <a data="1" href="tcm:1-2" data2="2">link text</a><!--CompLink tcm:1-2--> after text</p>
            // beforeWithLink: <p>Text <a data="1" href=
            // before: <p>Text
            // tcmUri: tcm:1-2
            // afterWithLink: " data2="2">link text</a><!--CompLink tcm:1-2--> after text</p>
            // after: link text</a><!--CompLink tcm:1-2--> after text</p>
            Pattern.compile("(?<beforeWithLink>(?<before>.*?)<a[^>]*?\\shref\\s*=\\s*\")(?<tcmUri>tcm:\\d++-\\d++)(?<afterWithLink>\"[^>]*>(?<after>.*))",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern END_LINK =
            // <p>Text <a data="1" href="resolved-link" data2="2">link text</a><!--CompLink tcm:1-2--> after text</p>
            // beforeWithLink: <p>Text <a data="1" href="resolved-link" data2="2">link text</a>
            // before: <p>Text <a data="1" href="resolved-link" data2="2">link text
            // tcmUri: tcm:1-2
            // after: after text</p>
            Pattern.compile("(?<beforeWithLink>(?<before>.*?)</a>)<!--CompLink\\s(?<tcmUri>tcm:\\d++-\\d++)-->(?<after>.*)",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    public static final int BUFFER_CAPACITY = 1024;

    private final LinkResolver linkResolver;

    private final ConfigService configService;

    private interface ResolveOrGetLink {
        String getByTcmUri(String tcmUrl, int localizationId);
    }

    @Autowired
    public RichTextLinkResolverImpl(@Qualifier("dxaLinkResolver") LinkResolver linkResolver, ConfigService configService) {
        this.linkResolver = linkResolver;
        this.configService = configService;
    }

    /**
     * Processes a rich text fragment trying to resolve links from it. In case of non-resolvable link, puts it into buffer.
     * <p>Reuse the same buffer if you have multiple fragments with possible same links parts in different fragments:</p>
     * <pre><code>
     *     RichTextLinkResolver resolver = new RichTextLinkResolverImpl();
     *     Set&lt;String&gt; buffer = new HashSet&lt;&gt;();
     *     String[] fragments = new String[]{"&lt;a href="tcm:1-2"&gt;text", "&lt;/a&gt;&lt;!--CompLink tcm:1-2--&gt;"};
     *     String[] resolved = new String[2];
     *     for (int i = 0; i &lt; fragments.length; i++) {
     *          resolved[i] = resolver.processFragment(fragments[i], buffer);
     *     }
     *     // here
     *     //   resolved = {"&lt;a href="resolved-link"&gt;text", "&lt;/a&gt;"};
     *     // or if link if unresolvable
     *     //   resolved = {"text", ""};</code></pre>
     *
     * @param fragment          fragment of a rich text to process
     * @param notResolvedBuffer buffer to put non resolvable links to, make sure it's modifiable
     * @return modified fragment
     */
    public String processFragment(@NotNull String fragment, @NotNull Map<String, String> batchOfLinks, @NotNull Set<String> notResolvedBuffer) {

        boolean richTextResolve = configService.getDefaults().isRichTextResolve();
        boolean richTextXmlnsRemove = configService.getDefaults().isRichTextXmlnsRemove();
        log.debug("RichTextResolver, dxa.defaults.rich-text-resolve = {}, dxa.web.link-resolver.remove-extension = {}", richTextResolve, richTextXmlnsRemove);
        if (log.isTraceEnabled()) log.trace("RichTextResolver, input fragment: '{}'", fragment);

        if (!richTextResolve) {
            log.info("RichText link resolving is turned off, don't do anything");
            return fragment;
        }

        String fragmentToProcess = richTextXmlnsRemove
                ? dropXlmns(fragment)
                : generateHref(fragment);
        String result = processEndLinks(processStartLinks(fragmentToProcess, batchOfLinks, notResolvedBuffer), notResolvedBuffer);
//        String result = processLinks(fragmentToProcess, batchOfLinks, notResolvedBuffer);
        Matcher withoutExcessiveSpaces = SPACES_FOR_REMOVAL.matcher(result);
        return withoutExcessiveSpaces.replaceAll("$1$2");
    }

    /**
     * Cleans up HTML fragment removing attributes from the {@code xlink:} namespace which may be found e.g. in DD4T representation.
     *
     * @param fragment rich text fragment to clean up
     * @return the same fragment with removed attributes
     */
    String dropXlmns(String fragment) {
        Matcher matcher = XMLNS_FOR_REMOVAL.matcher(fragment);
        return matcher
                .replaceAll("")
                .replaceAll(SPACES_FOR_REMOVAL.pattern(), "$1$2");
    }

    /**
     * Generates HREF based on xlmns:href.
     *
     * @param fragment rich text fragment to process
     * @return the same fragment with href added
     */
    String generateHref(String fragment) {
        Matcher matcher = XLINK_XLMNS_FOR_GENERATING_HREF.matcher(fragment);

        StringBuffer result = new StringBuffer(BUFFER_CAPACITY);
        while (matcher.find()) {
            if (matcher.group(0).matches(".* href\\s*=\\s*" + matcher.group("value") + ".*")) {
                //already has 'xmlns:href' and 'href', do not need to append 'href' at all
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement = matcher.group("before") + //<a data-cid="some value" id="some id" ...
                                matcher.group("prefix") +  // xmlns:href="link1" or xlink:href="link1"
                                matcher.group("tag") +  // 'href = ' or 'href='
                                matcher.group("value") + // "some href value"
                                " " + //a space
                                matcher.group("tag") + // 'href = ' or 'href='
                                matcher.group("value") + // "some href value"
                                matcher.group("after"); //...name="name of anchor"...>
            //so this replacement duplicates href from xmlns or xlink
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Generates HREF based on xlmns:href.
     *
     * @param fragmentString rich text fragment to process
     * @return Parses all the links out of the rich text fragment and returns them as a list
     */
    @NotNull
    public List<String> retrieveAllLinksFromFragment(@NotNull String fragmentString) {
        String fragment;

        List<String> links = new ArrayList<>();

        if (!configService.getDefaults().isRichTextResolve()) {
            log.debug("RichText link resolving is turned off, don't do anything");
            return links;
        }

        if (!fragmentString.contains("href=\"tcm")) {
            log.debug("No tcms in here to process.");
            return links;
        }

        fragment = configService.getDefaults().isRichTextXmlnsRemove()
                ? dropXlmns(fragmentString)
                : generateHref(fragmentString);

        log.debug("Fragment is: {}", fragment);

        long start = System.currentTimeMillis();

        Matcher startMatcher = COLLECT_LINK.matcher(fragment);
        while (startMatcher.find()) {
            links.add(startMatcher.group("tcmUri"));
        }

        log.debug(">>> matching took: {} ms.", (System.currentTimeMillis() - start));
        log.debug(">>> Found {} links", links.size());
        return links;
    }

    @NotNull
    private String processStartLinks(@NotNull String fragment,
                                     @NotNull Map<String, String> batchOfLinks,
                                     @NotNull Set<String> linksNotResolved) {
        Matcher startMatcher = START_LINK.matcher(fragment);

        while (startMatcher.matches()) {
            String tcmUri = startMatcher.group("tcmUri");
            String link = batchOfLinks.get(tcmUri);
            if (Strings.isNullOrEmpty(link)) {
                fragment = startMatcher.group("before") + startMatcher.group("after");
                if (linksNotResolved.add(tcmUri)) {
                    log.warn("Cannot resolve link to {}, suppressing link in fragment [{}]", tcmUri, fragment);
                }
            } else {
                log.debug("Resolved link to {} as {}", tcmUri, link);
                fragment = startMatcher.group("beforeWithLink") + link + startMatcher.group("afterWithLink");
            }
            startMatcher = START_LINK.matcher(fragment);
        }
        return fragment;
    }

    @NotNull
    private String processEndLinks(@NotNull String stringFragment, @NotNull Set<String> linksNotResolved) {
        String fragment = stringFragment;
        Matcher endMatcher = END_LINK.matcher(fragment);
        while (endMatcher.matches()) {
            String tcmUri = endMatcher.group("tcmUri");
            if (linksNotResolved.contains(tcmUri)) {
                log.trace("Tcm URI {} was not resolved, removing end </a> with marker", tcmUri);
                fragment = endMatcher.group("before") + endMatcher.group("after");
            } else {
                log.trace("Tcm URI {} was resolved, removing only marker, leaving </a>", tcmUri);
                fragment = endMatcher.group("beforeWithLink") + endMatcher.group("after");
            }

            endMatcher = END_LINK.matcher(fragment);
        }

        return fragment;
    }
}
