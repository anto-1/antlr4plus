/*
 * Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license distributed in
 * licenses/ANTLR4-RUST-TARGET-LICENSE.txt.
 */

package org.antlr.v4.codegen.target;

import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.Target;
import org.stringtemplate.v4.ST;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Source-only Rust target adapted for the ANTLR 4.13.2 API bundled by ANTLR4+.
 * Generated sources target the external antlr4rust 0.6 runtime; the runtime is
 * deliberately not bundled or executed by the plugin.
 */
public final class RustTarget extends Target {
	private static final Map<Character, String> TARGET_CHAR_VALUE_ESCAPE;

	static {
		HashMap<Character, String> map = new HashMap<>();
		addEscapedChar(map, '\t', 't');
		addEscapedChar(map, '\n', 'n');
		addEscapedChar(map, '\r', 'r');
		addEscapedChar(map, '"');
		addEscapedChar(map, '\'');
		addEscapedChar(map, '\\');
		TARGET_CHAR_VALUE_ESCAPE = map;
	}

	private static final Set<String> RESERVED_WORDS = new HashSet<>(Arrays.asList(
		"as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
		"false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
		"ref", "return", "Self", "self", "static", "struct", "super", "trait", "true", "type", "union",
		"unsafe", "use", "where", "while",
		"abstract", "become", "box", "do", "final", "gen", "macro", "override", "priv", "try", "typeof",
		"unsized", "virtual", "yield"
	));

	public RustTarget(CodeGenerator gen) {
		super(gen);
	}

	@Override
	public Map<Character, String> getTargetCharValueEscape() {
		return TARGET_CHAR_VALUE_ESCAPE;
	}

	@Override
	protected Set<String> getReservedWords() {
		return RESERVED_WORDS;
	}

	@Override
	public String getRecognizerFileName(boolean header) {
		return super.getRecognizerFileName(header).toLowerCase(Locale.ROOT);
	}

	@Override
	public String getListenerFileName(boolean header) {
		return supportFileName("listener");
	}

	@Override
	public String getVisitorFileName(boolean header) {
		return supportFileName("visitor");
	}

	@Override
	public String getBaseListenerFileName(boolean header) {
		return supportFileName("baselistener");
	}

	@Override
	public String getBaseVisitorFileName(boolean header) {
		return supportFileName("basevisitor");
	}

	private String supportFileName(String suffix) {
		assert gen.g.name != null;
		ST extension = getTemplates().getInstanceOf("codeFileExtension");
		return gen.g.name.toLowerCase(Locale.ROOT) + suffix + extension.render();
	}

	@Override
	public int getInlineTestSetWordSize() {
		return 32;
	}
}
