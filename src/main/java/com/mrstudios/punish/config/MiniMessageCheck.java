package com.mrstudios.punish.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Checks that a MiniMessage text only uses standard tags and the placeholders it's allowed. */
public final class MiniMessageCheck {

    /** Parses with nothing but the resolver passed in, so {@link TagChecker} sees every tag. */
    private static final MiniMessage VALIDATOR = MiniMessage.builder().tags(TagResolver.empty()).build();

    private MiniMessageCheck() {
    }

    /**
     * Why {@code text} can't be used, or null if it's fine.
     *
     * @param placeholders the placeholder names the text may use besides the standard tags
     */
    public static String problem(String text, Collection<String> placeholders) {
        List<TagResolver> known = new ArrayList<>();
        known.add(TagResolver.standard());
        for (String name : placeholders) {
            known.add(Placeholder.unparsed(name, ""));
        }
        TagChecker checker = new TagChecker(TagResolver.resolver(known));
        try {
            VALIDATOR.deserialize(text, checker);
        } catch (RuntimeException e) {
            return "is not valid MiniMessage (" + e.getMessage() + ")";
        }
        return checker.problem;
    }

    /**
     * Sees every tag in a message and records the first one that can't be used: an unknown name, a
     * placeholder from another message, or a standard tag with bad arguments. Escaped tags
     * ({@code \<player>}) are plain text and never reach it.
     */
    private static final class TagChecker implements TagResolver {

        private final TagResolver known;
        private String problem;

        TagChecker(TagResolver known) {
            this.known = known;
        }

        @Override
        public Tag resolve(String name, ArgumentQueue arguments, Context ctx) {
            if (known.has(name)) {
                try {
                    Tag tag = known.resolve(name, arguments, ctx);
                    if (tag != null) {
                        return tag;
                    }
                } catch (ParsingException e) {
                    // Falls through: reported below.
                }
                arguments.reset();
                record(name, arguments, " has invalid arguments");
            } else {
                record(name, arguments, " is not a valid tag here");
            }
            return Tag.selfClosingInserting(Component.empty());
        }

        private void record(String name, ArgumentQueue arguments, String reason) {
            if (problem != null) {
                return;
            }
            StringBuilder tag = new StringBuilder("<").append(name);
            while (arguments.hasNext()) {
                tag.append(':').append(arguments.pop().value());
            }
            problem = tag.append('>').append(reason).toString();
        }

        @Override
        public boolean has(String name) {
            return true;
        }
    }
}
