package fitnesse.wikitext.parser;

import fitnesse.wiki.WikiPage;
import util.Maybe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class Parser {
    private static final SymbolType[] emptyTypes = new SymbolType[] {};
    private static final Collection<SymbolType> emptyTypesList = new ArrayList<SymbolType>();
    private static final ArrayList<Symbol> emptySymbols = new ArrayList<Symbol>();

    public static Parser make(WikiPage page, String input) {
        return make(new ParsingPage(new WikiSourcePage(page)), input);
    }
    
    public static Parser make(ParsingPage currentPage, String input) {
        return make(currentPage, input, SymbolProvider.wikiParsingProvider);
    }

    public static Parser make(ParsingPage currentPage, String input, SymbolProvider provider) {
        return make(currentPage, input, new VariableFinder(currentPage), provider);
    }

    public static Parser make(ParsingPage currentPage, String input, VariableSource variableSource, SymbolProvider provider) {
        return new Parser(null, currentPage, new Scanner(new TextMaker(variableSource, currentPage.getNamedPage()), input), provider, variableSource, 0, emptyTypes, emptyTypes, emptyTypesList);
    }

    private ParsingPage currentPage;
    private SymbolProvider provider;
    private VariableSource variableSource;
    private Scanner scanner;
    private SymbolType[] terminators;
    private SymbolType[] ignoresFirst;
    private Collection<SymbolType> ends;
    private int priority;
    private Parser parent;

    public Parser(Parser parent, ParsingPage currentPage, Scanner scanner, SymbolProvider provider, VariableSource variableSource, int priority, SymbolType[] terminators, SymbolType[] ignoresFirst, Collection<SymbolType> ends) {
        this.parent = parent;
        this.currentPage = currentPage;
        this.scanner = scanner;
        this.provider = provider;
        this.priority = priority;
        this.terminators = terminators;
        this.ignoresFirst = ignoresFirst;
        this.ends = ends;
        this.variableSource = variableSource;
    }

    public ParsingPage getPage() { return currentPage; }
    public VariableSource getVariableSource() { return variableSource; }

    public Symbol getCurrent() { return scanner.getCurrent(); }
    public boolean atEnd() { return scanner.isEnd(); }
    public boolean atLast() { return scanner.isLast(); }
    public boolean isMoveNext(SymbolType type) { return moveNext(1).isType(type); }

    public Symbol moveNext(int count) {
        for (int i = 0; i < count; i++) scanner.moveNext();
        return scanner.getCurrent();
    }

    public List<Symbol> moveNext(SymbolType[] symbolTypes) {
        ArrayList<Symbol> tokens = new ArrayList<Symbol>();
        for (SymbolType type: symbolTypes) {
            Symbol current = moveNext(1);
            if (!current.isType(type)) return new ArrayList<Symbol>();
            tokens.add(current);
        }
        return tokens;
    }

    public List<Symbol> peek(SymbolType[] types) {
        List<Symbol> lookAhead = scanner.peek(types.length, provider, new ArrayList<SymbolType>());
        if (lookAhead.size() != types.length) return emptySymbols;
        for (int i = 0; i < lookAhead.size(); i++) {
            if (!lookAhead.get(i).isType(types[i])) return emptySymbols;
        }
        return lookAhead;
    }

    public String parseToAsString(SymbolType terminator) {
        int start = scanner.getOffset();
        scanner.markStart();
        parseTo(terminator);
        return scanner.substring(start, scanner.getOffset() - 1);
    }

    public String parseLiteral(SymbolType terminator) {
        return scanner.makeLiteral(terminator).getContent();
    }

    public Symbol parse(String input) {
        return new Parser(this, currentPage, new Scanner(new TextMaker(variableSource, currentPage.getNamedPage()), input), provider, variableSource, 0, emptyTypes, emptyTypes, emptyTypesList).parse();
    }

    public Symbol parseToIgnoreFirst(SymbolType type) {
        return parseToIgnoreFirst(new SymbolType[] {type});
    }

    public Symbol parseToIgnoreFirst(SymbolType[] types) {
        return new Parser(this, currentPage, scanner, provider, variableSource, 0, types, types, emptyTypesList).parse();
    }

    public Symbol parseToIgnoreFirstWithSymbols(SymbolType ignore, SymbolProvider provider) {
        SymbolType[] ignores = new SymbolType[] {ignore};
        return new Parser(this, currentPage, scanner, provider, variableSource, 0, ignores, ignores, emptyTypesList).parse();
    }
    
    public Symbol parseTo(SymbolType terminator) {
        return parseTo(terminator, 0);
    }

    public Symbol parseTo(SymbolType terminator, int priority) {
        return parseTo(new SymbolType[] {terminator}, priority);
    }

    public Symbol parseTo(SymbolType[] terminators, int priority) {
        return new Parser(this, currentPage, scanner, SymbolProvider.wikiParsingProvider, variableSource, priority, terminators, emptyTypes, emptyTypesList).parse();
    }

    public Symbol parseToWithSymbols(SymbolType terminator, SymbolProvider provider) {
        SymbolType[] terminators = new SymbolType[] {terminator};
        return parseToWithSymbols(terminators, provider);
    }

    public Symbol parseToWithSymbols(SymbolType[] terminators, SymbolProvider provider) {
        return new Parser(this, currentPage, scanner, provider, variableSource, 0, terminators, emptyTypes, emptyTypesList).parse();
    }

    public Symbol parseToEnd(SymbolType end) {
        return new Parser(this, currentPage, scanner, SymbolProvider.wikiParsingProvider, variableSource, 0, emptyTypes, emptyTypes, Arrays.asList(end)).parse();
    }

    public Symbol parseToEnds(int priority, SymbolProvider provider, SymbolType[] moreEnds) {
        SymbolProvider newProvider = new SymbolProvider(provider);
        newProvider.addTypes(ends);
        newProvider.addTypes(Arrays.asList(terminators));
        newProvider.addTypes(Arrays.asList(moreEnds));
        return new Parser(this, currentPage, scanner, newProvider, variableSource, priority, emptyTypes, emptyTypes, Arrays.asList(moreEnds)).parse();
    }

    public Symbol parse() {
        Symbol result = new Symbol(SymbolType.SymbolList);
        ArrayList<SymbolType> ignore = new ArrayList<SymbolType>();
        ignore.addAll(Arrays.asList(ignoresFirst));
        while (true) {
            Scanner backup = new Scanner(scanner);
            scanner.moveNextIgnoreFirst(provider, ignore);
            if (scanner.isEnd()) break;
            Symbol currentToken = scanner.getCurrent();
            if (contains(ends, currentToken.getType()) || parentOwns(currentToken.getType(), priority)) {
                scanner.copy(backup);
                break;
            }
            if (contains(terminators, currentToken.getType())) break;
            Rule currentRule = currentToken.getType().getWikiRule();
            if (currentRule != null) {
                Maybe<Symbol> parsedSymbol = currentRule.parse(currentToken, this);
                if (parsedSymbol.isNothing()) {
                    ignore.add(currentToken.getType());
                    scanner.copy(backup);
                }
                else {
                    result.add(parsedSymbol.getValue());
                    ignore.clear();
                }
            }
            else {
                result.add(currentToken);
                ignore.clear();
            }
        }
        return result;
    }

    private boolean contains(SymbolType[] terminators, SymbolType currentType) {
        for (SymbolType terminator: terminators)
            if (currentType == terminator) return true;
        return false;
    }

    private boolean parentOwns(SymbolType current, int priority) {
        if (parent == null) return false;
        if (parent.priority > priority && parent.contains(parent.terminators, current)) return true;
        return parent.parentOwns(current, priority);
    }

    private boolean contains(Iterable<SymbolType> terminators, SymbolType currentType) {
        for (SymbolType terminator: terminators)
            if (currentType == terminator) return true;
        return false;
    }
}
