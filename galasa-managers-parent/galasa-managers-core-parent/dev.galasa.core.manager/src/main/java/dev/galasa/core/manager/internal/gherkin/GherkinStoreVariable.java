package dev.galasa.core.manager.internal.gherkin;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.galasa.ManagerException;
import dev.galasa.framework.spi.ConfigurationPropertyStoreException;
import dev.galasa.framework.spi.IConfigurationPropertyStoreService;
import dev.galasa.framework.spi.language.gherkin.GherkinKeyword;

public class GherkinStoreVariable {

    public final static GherkinKeyword keyword = GherkinKeyword.GIVEN;

    public final static Pattern pattern = Pattern.compile("<(\\w+)> is test property ([\\w.]+)");

    public static void execute(Matcher matcherStoreVariable, IConfigurationPropertyStoreService cpsTest, Map<String, Object> testVariables)
            throws ManagerException {
        String variableName = matcherStoreVariable.group(1);
        String cpsProp = matcherStoreVariable.group(2);
        String cpsPrefix = cpsProp.substring(0, cpsProp.indexOf("."));
        String cpsSuffix = cpsProp.substring(cpsProp.indexOf(".") + 1);
        try {
            String cpsValue = cpsTest.getProperty(cpsPrefix, cpsSuffix);
            testVariables.put(variableName, cpsValue);
        } catch (ConfigurationPropertyStoreException e) {
            throw new ManagerException("Unable to access CPS", e);
        }
    }
}