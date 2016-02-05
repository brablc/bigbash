package de.zalando.bigbash.integration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.zalando.bigbash.commandline.BashCompiler;
import de.zalando.bigbash.entities.CompressionType;
import de.zalando.bigbash.entities.FileMappingProperties;
import de.zalando.bigbash.util.BashStarter;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Created by bvonloesch on 6/20/14.
 */
@RunWith(Parameterized.class)
public class IntegrationTesterIT {

    public static final String TEST_FILE_NAME = "src/test/resources/integrationTests";
    public static final String DELIMITER = ";";
    private final String output;
    private final Map<String, String> tableContent;
    private final String sql;

    public IntegrationTesterIT(final String sql, final Map<String, String> tableContent, final String output) {
        super();
        this.sql = sql;
        this.tableContent = tableContent;
        this.output = output;
        FileMappingProperties.outputDelimiter = DELIMITER;
    }

    public static Iterator<Object[]> getTestIterator(final BufferedReader reader) {
        return new Iterator<Object[]>() {
            Object[] next;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }

                next = next();
                return next != null;
            }

            @Override
            public Object[] next() {
                if (next != null) {
                    Object[] tt = next;
                    next = null;
                    return tt;
                }

                String line;
                try {
                    StringBuilder statement = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("<")) {
                            break;
                        }

                        if (statement.length() > 0) {
                            statement.append("\n");
                        }

                        statement.append(line);
                    }

                    if (statement.length() == 0) {
                        return null;
                    }

                    Map<String, String> input = Maps.newHashMap();
                    while (line.startsWith("<")) {
                        String tableName = line.substring(1).trim();
                        StringBuilder tableContent = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith(">") || line.startsWith("<")) {
                                break;
                            }

                            if (tableContent.length() > 0) {
                                tableContent.append("\n");
                            }

                            tableContent.append(line);
                        }

                        input.put(tableName, tableContent.toString());
                    }

                    if (!line.startsWith(">")) {
                        throw new RuntimeException("Expect > after table definition");
                    }

                    StringBuilder output = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("-")) {
                            break;
                        }

                        output.append(line);
                        output.append("\n");
                    }

                    return ImmutableList.of(statement.toString(), input, output.toString()).toArray();
                } catch (IOException e) {
                    throw new RuntimeException("IoException while reading test file");
                }

            }

            @Override
            public void remove() { }
        };
    }

    @Parameterized.Parameters
    public static Collection<Object[]> regExValues() throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(TEST_FILE_NAME));
        ArrayList<Object[]> l = Lists.newArrayList(getTestIterator(br));
        br.close();
        return l;
    }

    @Test
    public void checkSqlTest() throws Exception {
        Map<String, FileMappingProperties> fileMappingPropertiesMap = Maps.newHashMap();
        for (Map.Entry<String, String> table : tableContent.entrySet()) {
            File tableFile = File.createTempFile("table_" + table.getKey(), "");
            tableFile.deleteOnExit();
            FileUtils.writeStringToFile(tableFile, table.getValue());
            fileMappingPropertiesMap.put(table.getKey(),
                new FileMappingProperties(tableFile.getAbsolutePath(), CompressionType.NONE, DELIMITER));
        }

        BashCompiler compiler = new BashCompiler();
        String bashScript = compiler.compile(sql, fileMappingPropertiesMap, false);
        BashStarter starter = new BashStarter(".", bashScript);
        String scriptOutput = starter.startScript(bashScript);
        System.out.println(sql);
        System.out.println(bashScript);
        System.out.flush();
        assertEquals(output, scriptOutput);
    }

    @Test
    public void checkSortSqlTest() throws Exception {
        Map<String, FileMappingProperties> fileMappingPropertiesMap = Maps.newHashMap();
        for (Map.Entry<String, String> table : tableContent.entrySet()) {
            File tableFile = File.createTempFile("table_" + table.getKey(), "");
            tableFile.deleteOnExit();
            FileUtils.writeStringToFile(tableFile, table.getValue());
            fileMappingPropertiesMap.put(table.getKey(),
                    new FileMappingProperties(tableFile.getAbsolutePath(), CompressionType.NONE, DELIMITER));
        }

        BashCompiler compiler = new BashCompiler();
        String bashScript = compiler.compile(sql, fileMappingPropertiesMap, true);
        BashStarter starter = new BashStarter(".", bashScript);
        String scriptOutput = starter.startScript(bashScript);
        System.out.println(sql);
        System.out.println(bashScript);
        System.out.flush();
        assertEquals(output, scriptOutput);
    }

}
