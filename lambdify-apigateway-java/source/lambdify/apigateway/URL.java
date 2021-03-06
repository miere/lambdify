package lambdify.apigateway;

import lombok.NonNull;
import lombok.Value;
import lombok.val;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public interface URL {

    static URLMatcher compile(String url ) {
        val newTokens = new ArrayList<CompiledEntry>();
        for ( val it : tokenize(url) ) {
            newTokens.add(
                ( it.length() > 1 && it.charAt(1) == ':' )
                    ? new PlaceHolder(it.substring(1))
                    : new Equals(it) );
        }
        return new URLMatcher(newTokens);
    }

    static List<String> tokenize( @NonNull String path ) {
        if ( path.equals("/") ) return Collections.singletonList( "/" );
        else if ( path.isEmpty() ) return Collections.emptyList();
        else {
            val tokens = new ArrayList<String>();
            val url = (path.charAt(path.length()-1) != '/') ? path
                         : path.substring(0, path.length() - 1);
            for ( val it : url.split("/") ) {
                if (!it.isEmpty())
                    tokens.add("/"+it);
            }
            return tokens;
        }
    }

    @Value class URLMatcher {

        @NonNull final List<CompiledEntry> compiled;

        public boolean matches(List<String> tokens, Map<String,String> ctx ) {
            if ( tokens.size() == compiled.size() ) {
                for (int i = 0; i < tokens.size(); i++) {
                    val token = tokens.get(i);
                    val entry = compiled.get(i);
                    if (!entry.apply(token, ctx))
                        return false;
                }
                return true;
            }
            return false;
        }
    }

    @Value class Equals implements CompiledEntry {

        @NonNull final String value;

        @Override
        public Boolean apply(String s, Map<String, String> stringStringMap) {
            return value.equals(s);
        }
    }

    @Value class PlaceHolder implements CompiledEntry {

        @NonNull final String key;

        @Override
        public Boolean apply(String value, Map<String, String> ctx) {
            ctx.put(key, value);
            return true;
        }
    }

    interface CompiledEntry extends BiFunction<String, Map<String,String>, Boolean>{}
}
