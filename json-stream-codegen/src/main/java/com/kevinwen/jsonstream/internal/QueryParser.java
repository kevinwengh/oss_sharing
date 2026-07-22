/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.kevinwen.jsonstream.QueryParseException;
import com.kevinwen.jsonstream.internal.QueryModel.Binary;
import com.kevinwen.jsonstream.internal.QueryModel.Expression;
import com.kevinwen.jsonstream.internal.QueryModel.Field;
import com.kevinwen.jsonstream.internal.QueryModel.FunctionCall;
import com.kevinwen.jsonstream.internal.QueryModel.Index;
import com.kevinwen.jsonstream.internal.QueryModel.Key;
import com.kevinwen.jsonstream.internal.QueryModel.Literal;
import com.kevinwen.jsonstream.internal.QueryModel.LiteralType;
import com.kevinwen.jsonstream.internal.QueryModel.PathStep;
import com.kevinwen.jsonstream.internal.QueryModel.Projection;
import com.kevinwen.jsonstream.internal.QueryModel.Query;
import com.kevinwen.jsonstream.internal.QueryModel.Unary;

/** Recursive-descent parser with explicit resource limits. */
public final class QueryParser {
  private static final int MAX_QUERY_LENGTH = 16_384;
  private static final int MAX_PROJECTIONS = 256;
  private static final int MAX_NESTING = 64;
  private static final int MAX_AST_NODES = 4_096;

  private final List<Token> tokens;
  private int current;
  private int nesting;

  public QueryParser(String text) {
    if (text.length() > MAX_QUERY_LENGTH) {
      throw new QueryParseException("Query exceeds " + MAX_QUERY_LENGTH + " characters");
    }
    tokens = new Lexer(text).scan();
  }

  public Query parse() {
    consume(TokenType.SELECT, "Expected SELECT");
    List<Projection> projections = new ArrayList<>();
    Set<String> aliases = new HashSet<>();
    do {
      if (projections.size() == MAX_PROJECTIONS) {
        throw error(peek(), "Query exceeds " + MAX_PROJECTIONS + " projections");
      }
      Expression expression = expression();
      String alias;
      if (match(TokenType.AS)) {
        alias = consume(TokenType.IDENTIFIER, "Expected output alias after AS").text();
      } else if (expression instanceof Field field && field.path().getLast() instanceof Key key) {
        alias = key.name();
      } else {
        throw error(peek(), "Computed projections require AS and an output alias");
      }
      if (!aliases.add(alias)) {
        throw error(previous(), "Duplicate output alias: " + alias);
      }
      projections.add(new Projection(expression, alias));
    } while (match(TokenType.COMMA));

    if (match(TokenType.FROM)) {
      Token source = consume(TokenType.IDENTIFIER, "Expected input after FROM");
      if (!source.text().equalsIgnoreCase("input")) {
        throw error(source, "Only FROM input is supported");
      }
    }
    Expression predicate = match(TokenType.WHERE) ? expression() : null;
    match(TokenType.SEMICOLON);
    consume(TokenType.EOF, "Unexpected query content");
    Query query = new Query(projections, predicate);
    validateAstComplexity(query);
    return query;
  }

  private Expression expression() {
    return or();
  }

  private Expression or() {
    Expression result = and();
    while (match(TokenType.OR)) {
      result = new Binary("OR", result, and());
    }
    return result;
  }

  private Expression and() {
    Expression result = comparison();
    while (match(TokenType.AND)) {
      result = new Binary("AND", result, comparison());
    }
    return result;
  }

  private Expression comparison() {
    Expression result = additive();
    while (match(
        TokenType.EQUAL,
        TokenType.NOT_EQUAL,
        TokenType.LESS,
        TokenType.LESS_EQUAL,
        TokenType.GREATER,
        TokenType.GREATER_EQUAL)) {
      String operator = previous().type() == TokenType.NOT_EQUAL ? "!=" : previous().text();
      result = new Binary(operator, result, additive());
    }
    return result;
  }

  private Expression additive() {
    Expression result = multiplicative();
    while (match(TokenType.PLUS, TokenType.MINUS)) {
      String operator = previous().text();
      result = new Binary(operator, result, multiplicative());
    }
    return result;
  }

  private Expression multiplicative() {
    Expression result = unary();
    while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
      String operator = previous().text();
      result = new Binary(operator, result, unary());
    }
    return result;
  }

  private Expression unary() {
    if (match(TokenType.NOT, TokenType.PLUS, TokenType.MINUS)) {
      Token operator = previous();
      enter(operator);
      try {
        return new Unary(operator.type() == TokenType.NOT ? "NOT" : operator.text(), unary());
      } finally {
        nesting--;
      }
    }
    return primary();
  }

  private Expression primary() {
    if (match(TokenType.STRING)) {
      return new Literal(LiteralType.STRING, previous().text());
    }
    if (match(TokenType.NUMBER)) {
      return new Literal(LiteralType.NUMBER, previous().text());
    }
    if (match(TokenType.TRUE, TokenType.FALSE)) {
      return new Literal(
          LiteralType.BOOLEAN, previous().type() == TokenType.TRUE ? "true" : "false");
    }
    if (match(TokenType.NULL)) {
      return new Literal(LiteralType.NULL, "null");
    }
    if (match(TokenType.LEFT_PAREN)) {
      Token opening = previous();
      enter(opening);
      try {
        Expression nested = expression();
        consume(TokenType.RIGHT_PAREN, "Expected ')' after expression");
        return nested;
      } finally {
        nesting--;
      }
    }
    if (match(TokenType.IDENTIFIER)) {
      Token identifier = previous();
      if (match(TokenType.LEFT_PAREN)) {
        enter(identifier);
        try {
          List<Expression> arguments = new ArrayList<>();
          if (!check(TokenType.RIGHT_PAREN)) {
            do {
              arguments.add(expression());
            } while (match(TokenType.COMMA));
          }
          consume(TokenType.RIGHT_PAREN, "Expected ')' after function arguments");
          return new FunctionCall(identifier.text().toUpperCase(Locale.ROOT), arguments);
        } finally {
          nesting--;
        }
      }
      List<PathStep> path = new ArrayList<>();
      path.add(new Key(identifier.text()));
      while (true) {
        if (match(TokenType.DOT)) {
          path.add(new Key(consume(TokenType.IDENTIFIER, "Expected field name after '.'").text()));
        } else if (match(TokenType.LEFT_BRACKET)) {
          Token indexToken = consume(TokenType.NUMBER, "Expected array index after '['");
          path.add(new Index(parseArrayIndex(indexToken)));
          consume(TokenType.RIGHT_BRACKET, "Expected ']' after array index");
        } else {
          break;
        }
      }
      return new Field(path);
    }
    throw error(peek(), "Expected expression");
  }

  private int parseArrayIndex(Token token) {
    String text = token.text();
    for (int position = 0; position < text.length(); position++) {
      if (text.charAt(position) < '0' || text.charAt(position) > '9') {
        throw error(token, "Array index must be a non-negative integer");
      }
    }
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw error(token, "Array index exceeds " + Integer.MAX_VALUE);
    }
  }

  private void enter(Token token) {
    if (++nesting > MAX_NESTING) {
      throw error(token, "Expression nesting exceeds " + MAX_NESTING);
    }
  }

  private static void validateAstComplexity(Query query) {
    ArrayDeque<ExpressionFrame> pending = new ArrayDeque<>();
    query
        .projections()
        .forEach(projection -> pending.add(new ExpressionFrame(projection.expression(), 1)));
    if (query.predicate() != null) {
      pending.add(new ExpressionFrame(query.predicate(), 1));
    }
    int nodes = 0;
    while (!pending.isEmpty()) {
      ExpressionFrame frame = pending.removeLast();
      if (++nodes > MAX_AST_NODES) {
        throw new QueryParseException("Expression exceeds " + MAX_AST_NODES + " syntax nodes");
      }
      if (frame.depth() > MAX_NESTING) {
        throw new QueryParseException("Expression nesting exceeds " + MAX_NESTING);
      }
      int childDepth = frame.depth() + 1;
      switch (frame.expression()) {
        case Literal ignored -> {}
        case Field ignored -> {}
        case Unary unary -> pending.add(new ExpressionFrame(unary.operand(), childDepth));
        case Binary binary -> {
          pending.add(new ExpressionFrame(binary.left(), childDepth));
          pending.add(new ExpressionFrame(binary.right(), childDepth));
        }
        case FunctionCall function ->
            function
                .arguments()
                .forEach(argument -> pending.add(new ExpressionFrame(argument, childDepth)));
      }
    }
  }

  private record ExpressionFrame(Expression expression, int depth) {}

  private boolean match(TokenType... types) {
    for (TokenType type : types) {
      if (check(type)) {
        current++;
        return true;
      }
    }
    return false;
  }

  private boolean check(TokenType type) {
    return peek().type() == type;
  }

  private Token consume(TokenType type, String message) {
    if (check(type)) {
      return tokens.get(current++);
    }
    throw error(peek(), message);
  }

  private Token peek() {
    return tokens.get(current);
  }

  private Token previous() {
    return tokens.get(current - 1);
  }

  private static QueryParseException error(Token token, String message) {
    return new QueryParseException(message + " at character " + token.position());
  }

  private enum TokenType {
    SELECT,
    FROM,
    WHERE,
    AS,
    TRUE,
    FALSE,
    NULL,
    AND,
    OR,
    NOT,
    IDENTIFIER,
    STRING,
    NUMBER,
    COMMA,
    DOT,
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    SEMICOLON,
    EOF
  }

  private record Token(TokenType type, String text, int position) {}

  private static final class Lexer {
    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private int current;

    private Lexer(String text) {
      this.text = text;
    }

    private List<Token> scan() {
      while (current < text.length()) {
        int start = current;
        char character = text.charAt(current++);
        switch (character) {
          case ' ', '\t', '\r', '\n' -> {}
          case ',' -> add(TokenType.COMMA, ",", start);
          case '.' -> add(TokenType.DOT, ".", start);
          case '(' -> add(TokenType.LEFT_PAREN, "(", start);
          case ')' -> add(TokenType.RIGHT_PAREN, ")", start);
          case '[' -> add(TokenType.LEFT_BRACKET, "[", start);
          case ']' -> add(TokenType.RIGHT_BRACKET, "]", start);
          case '+' -> add(TokenType.PLUS, "+", start);
          case '-' -> add(TokenType.MINUS, "-", start);
          case '*' -> add(TokenType.STAR, "*", start);
          case '/' -> add(TokenType.SLASH, "/", start);
          case '%' -> add(TokenType.PERCENT, "%", start);
          case '=' -> add(TokenType.EQUAL, "=", start);
          case ';' -> add(TokenType.SEMICOLON, ";", start);
          case '!' -> {
            require('=', start, "Expected '=' after '!'");
            add(TokenType.NOT_EQUAL, "!=", start);
          }
          case '<' -> {
            if (take('=')) {
              add(TokenType.LESS_EQUAL, "<=", start);
            } else if (take('>')) {
              add(TokenType.NOT_EQUAL, "<>", start);
            } else {
              add(TokenType.LESS, "<", start);
            }
          }
          case '>' ->
              add(
                  take('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER,
                  text.substring(start, current),
                  start);
          case '\'' -> string(start);
          default -> {
            if (isDigit(character)) {
              number(start);
            } else if (isIdentifierStart(character)) {
              identifier(start);
            } else {
              throw new QueryParseException(
                  "Unexpected character '" + character + "' at character " + start);
            }
          }
        }
      }
      tokens.add(new Token(TokenType.EOF, "", current));
      return List.copyOf(tokens);
    }

    private void string(int start) {
      StringBuilder value = new StringBuilder();
      while (current < text.length()) {
        char character = text.charAt(current++);
        if (character == '\'') {
          if (take('\'')) {
            value.append('\'');
          } else {
            add(TokenType.STRING, value.toString(), start);
            return;
          }
        } else {
          value.append(character);
        }
      }
      throw new QueryParseException("Unterminated string at character " + start);
    }

    private void number(int start) {
      while (current < text.length() && isDigit(text.charAt(current))) {
        current++;
      }
      if (current < text.length()
          && text.charAt(current) == '.'
          && current + 1 < text.length()
          && isDigit(text.charAt(current + 1))) {
        current++;
        while (current < text.length() && isDigit(text.charAt(current))) {
          current++;
        }
      }
      if (current < text.length() && (text.charAt(current) == 'e' || text.charAt(current) == 'E')) {
        int exponent = current++;
        if (current < text.length()
            && (text.charAt(current) == '+' || text.charAt(current) == '-')) {
          current++;
        }
        int digits = current;
        while (current < text.length() && isDigit(text.charAt(current))) {
          current++;
        }
        if (digits == current) {
          throw new QueryParseException("Invalid exponent at character " + exponent);
        }
      }
      add(TokenType.NUMBER, text.substring(start, current), start);
    }

    private void identifier(int start) {
      while (current < text.length() && isIdentifierPart(text.charAt(current))) {
        current++;
      }
      String value = text.substring(start, current);
      TokenType type =
          switch (value.toUpperCase(Locale.ROOT)) {
            case "SELECT" -> TokenType.SELECT;
            case "FROM" -> TokenType.FROM;
            case "WHERE" -> TokenType.WHERE;
            case "AS" -> TokenType.AS;
            case "TRUE" -> TokenType.TRUE;
            case "FALSE" -> TokenType.FALSE;
            case "NULL" -> TokenType.NULL;
            case "AND" -> TokenType.AND;
            case "OR" -> TokenType.OR;
            case "NOT" -> TokenType.NOT;
            default -> TokenType.IDENTIFIER;
          };
      add(type, value, start);
    }

    private void require(char expected, int start, String message) {
      if (!take(expected)) {
        throw new QueryParseException(message + " at character " + start);
      }
    }

    private boolean take(char expected) {
      if (current < text.length() && text.charAt(current) == expected) {
        current++;
        return true;
      }
      return false;
    }

    private void add(TokenType type, String value, int start) {
      tokens.add(new Token(type, value, start));
    }

    private static boolean isDigit(char value) {
      return value >= '0' && value <= '9';
    }

    private static boolean isIdentifierStart(char value) {
      return value == '_' || Character.isLetter(value);
    }

    private static boolean isIdentifierPart(char value) {
      return value == '_' || Character.isLetterOrDigit(value);
    }
  }
}
