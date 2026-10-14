/*
 * [The "BSD license"]
 *  Copyright (c) 2013 Terence Parr
 *  Copyright (c) 2013 Sam Harwell
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

parser grammar ANTLRv4Parser;

options {
	tokenVocab=ANTLRv4Lexer;
}

// Entry point for a complete grammar.
grammarSpec
	:	DOC_COMMENT*
		grammarType identifier SEMI
		prequelConstruct*
		rules
		modeSpec*
		EOF
	;

grammarType
	:	(	LEXER GRAMMAR
		|	PARSER GRAMMAR
		|	GRAMMAR
		)
	;

// Declarations that precede the rules.
prequelConstruct
	:	optionsSpec
	|	delegateGrammars
	|	tokensSpec
	|	channelsSpec
	|	action
	;

optionsSpec
	:	OPTIONS LBRACE (option SEMI)* RBRACE
	;

option
	:	identifier ASSIGN optionValue
	;

optionValue
	:	identifier (DOT identifier)*
	|	STRING_LITERAL
	|	actionBlock
	|	INT
	;

delegateGrammars
	:	IMPORT delegateGrammar (COMMA delegateGrammar)* SEMI
	;

delegateGrammar
	:	identifier ASSIGN identifier
	|	identifier
	;

tokensSpec
	:	TOKENS LBRACE idList? RBRACE
	;

channelsSpec
	:	CHANNELS LBRACE idList? RBRACE
	;

idList
	: identifier ( COMMA identifier )* COMMA?
	;

action
	:	AT (actionScopeName COLONCOLON)? identifier actionBlock
	;

   // Action scopes may use lexer/parser keywords as names.
actionScopeName
	:	identifier
	|	LEXER
	|	PARSER
	;

actionBlock
   : BEGIN_ACTION ACTION_CONTENT* END_ACTION
   ;

argActionBlock
   : BEGIN_ARGUMENT ARGUMENT_CONTENT* END_ARGUMENT
   ;

modeSpec
	:	MODE identifier SEMI lexerRule*
	;

rules
	:	ruleSpec*
	;

ruleSpec
	:	parserRuleSpec
	|	lexerRule
	;

parserRuleSpec
	:	DOC_COMMENT*
        RULE_REF argActionBlock?
        ruleReturns? throwsSpec? localsSpec?
		rulePrequel*
		COLON
            ruleBlock
		SEMI
		exceptionGroup
	;

exceptionGroup
	:	exceptionHandler* finallyClause?
	;

exceptionHandler
	:	CATCH argActionBlock actionBlock
	;

finallyClause
	:	FINALLY actionBlock
	;

rulePrequel
	:	optionsSpec
	|	ruleAction
	;

ruleReturns
	:	RETURNS argActionBlock
	;

throwsSpec
	:	THROWS qid (COMMA qid)*
	;

localsSpec
	:	LOCALS argActionBlock
	;

ruleAction
	:	AT identifier actionBlock
	;

ruleBlock
	:	ruleAltList
	;

ruleAltList
	:	labeledAlt (OR labeledAlt)*
	;

labeledAlt
	:	alternative (POUND identifier)?
	;

lexerRule
	:	DOC_COMMENT* FRAGMENT?
		TOKEN_REF optionsSpec? COLON lexerRuleBlock SEMI
	;

lexerRuleBlock
	:	lexerAltList
	;

lexerAltList
	:	lexerAlt (OR lexerAlt)*
	;

lexerAlt
	:	lexerElements lexerCommands?
	|
   // Empty alternatives are valid.
	;

lexerElements
	:	lexerElement+
	;

lexerElement
	:	labeledLexerElement ebnfSuffix?
	|	lexerAtom ebnfSuffix?
	|	lexerBlock ebnfSuffix?
	|	actionElement
	;

labeledLexerElement
	:	identifier (ASSIGN|PLUS_ASSIGN)
		(	lexerAtom
		|	lexerBlock
		)
	;

lexerBlock
	:	LPAREN (optionsSpec COLON)? lexerAltList RPAREN
	;

// Examples: channel(HIDDEN), skip, pushMode(INSIDE), popMode.
lexerCommands
	:	RARROW lexerCommand (COMMA lexerCommand)*
	;

lexerCommand
	:	lexerCommandName LPAREN lexerCommandExpr RPAREN
	|	lexerCommandName
	;

lexerCommandName
	:	identifier
	|	MODE
	;

lexerCommandExpr
	:	identifier
	|	INT
	;

altList
	:	alternative (OR alternative)*
	;

alternative
	:	elementOptions? element+
    |
    // Empty alternatives are valid.
	;

element
	:	labeledElement (ebnfSuffix |)
	|	atom (ebnfSuffix |)
	|	ebnf
	|	actionElement
	;

actionElement
    :   actionBlock QUESTION? elementOptions?
    ;

labeledElement
	:	identifier (ASSIGN|PLUS_ASSIGN)
		(	atom
		|	block
		)
	;

ebnf:	block blockSuffix?
	;

blockSuffix
	:	ebnfSuffix
	;

ebnfSuffix
	:	QUESTION QUESTION?
	|	STAR QUESTION?
	|	PLUS QUESTION?
	;

lexerAtom
	:	range
	|	terminal
	|	RULE_REF
	|	notSet
	|	LEXER_CHAR_SET
	|	DOT elementOptions?
	;

atom
	:	range // Ranges are only valid in lexer rules.
	|	terminal
	|	ruleref
	|	notSet
	|	DOT elementOptions?
	;

notSet
	:	NOT setElement
	|	NOT blockSet
	;

blockSet
	:	LPAREN setElement (OR setElement)* RPAREN
	;

setElement
	:	TOKEN_REF elementOptions?
	|	STRING_LITERAL elementOptions?
	|	range
	|	LEXER_CHAR_SET
	;

block
	:	LPAREN
		( optionsSpec? ruleAction* COLON )?
		altList
		RPAREN
	;

ruleref
	:	RULE_REF argActionBlock? elementOptions?
	;

range
	: STRING_LITERAL RANGE STRING_LITERAL
	;

terminal
	:   TOKEN_REF elementOptions?
	|   STRING_LITERAL elementOptions?
	;

elementOptions
	:	LT elementOption (COMMA elementOption)* GT
	;

elementOption
	:
		qid
	|
		identifier ASSIGN optionValue
	;

identifier	:	RULE_REF
	|	TOKEN_REF
	;

qid : identifier (DOT identifier)*
    ;
