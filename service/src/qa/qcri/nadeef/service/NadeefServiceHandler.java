/*
 * QCRI, NADEEF LICENSE
 * NADEEF is an extensible, generalized and easy-to-deploy data cleaning platform built at QCRI.
 * NADEEF means "Clean" in Arabic
 *
 * Copyright (c) 2011-2013, Qatar Foundation for Education, Science and Community Development (on
 * behalf of Qatar Computing Research Institute) having its principle place of business in Doha,
 * Qatar with the registered address P.O box 5825 Doha, Qatar (hereinafter referred to as "QCRI")
 *
 * NADEEF has patent pending nevertheless the following is granted.
 * NADEEF is released under the terms of the MIT License, (http://opensource.org/licenses/MIT).
 */

package qa.qcri.nadeef.service;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.thrift.TException;
import qa.qcri.nadeef.core.datamodel.CleanPlan;
import qa.qcri.nadeef.core.datamodel.NadeefConfiguration;
import qa.qcri.nadeef.core.datamodel.Rule;
import qa.qcri.nadeef.core.datamodel.Schema;
import qa.qcri.nadeef.core.exception.InvalidRuleException;
import qa.qcri.nadeef.core.util.sql.DBMetaDataTool;
import qa.qcri.nadeef.core.util.RuleBuilder;
import qa.qcri.nadeef.service.thrift.*;
import qa.qcri.nadeef.tools.CommonTools;
import qa.qcri.nadeef.tools.DBConfig;
import qa.qcri.nadeef.tools.Tracer;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * NadeefServiceHandler handles request for NADEEF service.
 */
// TODO: speedup the compiling stage by using object caching.
public class NadeefServiceHandler implements TNadeefService.Iface {
    private static Tracer tracer = Tracer.getTracer(NadeefServiceHandler.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public String generate(TRule tRule, String tableName) throws TNadeefRemoteException {
        String result = "";
        String type = tRule.getType();
        String code = tRule.getCode();
        String name = tRule.getName();

        if (type.equalsIgnoreCase("udf")) {
            result = code;
        } else {
            String[] codeLines = code.split("\n");
            List<String> codes = Lists.newArrayList(codeLines);

            try {
                Schema schema =
                    DBMetaDataTool.getSchema(
                        NadeefConfiguration.getDbConfig(), tableName
                    );
                RuleBuilder ruleBuilder =
                    NadeefConfiguration.tryGetRuleBuilder(type.toString());
                if (ruleBuilder != null) {
                    Collection<File> javaFiles =
                        ruleBuilder
                            .name(name)
                            .schema(schema)
                            .table(tableName)
                            .value(codes)
                            .generate();
                    // TODO: currently only picks the first generated file
                    File codeFile = javaFiles.iterator().next();
                    result = Files.toString(codeFile, Charset.defaultCharset());
                }
            } catch (Exception ex) {
                tracer.err("Code generation failed.", ex);
                TNadeefRemoteException re = new TNadeefRemoteException();
                re.setType(TNadeefExceptionType.UNKNOWN);
                re.setMessage(ex.getMessage());
                throw re;
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verify(TRule rule) {
        String type = rule.getType();
        String code = rule.getCode();
        String name = rule.getName();
        boolean result = true;
        try {
            if (type.equalsIgnoreCase("udf")) {
                Path outputPath =
                    FileSystems.getDefault().getPath(
                        NadeefConfiguration.getOutputPath().toString(),
                        name + ".java"
                    );

                Files.write(
                    code.getBytes(StandardCharsets.UTF_8),
                    outputPath.toFile()
                );

                if (!CommonTools.compileFile(outputPath.toFile())) {
                    result = false;
                }
            }
        } catch (Exception ex) {
            tracer.err("Exception happens in verify.", ex);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String detect(TRule rule, String table1, String table2) throws TNadeefRemoteException {
        tracer.info("Detect rule " + rule.getName() + "[" + rule.getType() + "]");
        if (!verify(rule)) {
            TNadeefRemoteException ex = new TNadeefRemoteException();
            ex.setType(TNadeefExceptionType.COMPILE_ERROR);
            throw ex;
        }

        List<String> tables = Lists.newArrayList();
        tables.add(table1);
        if (table2 != null && !table2.isEmpty()) {
            tables.add(table2);
        }

        try {
            NadeefJobScheduler scheduler = NadeefJobScheduler.getInstance();
            DBConfig config = new DBConfig(NadeefConfiguration.getDbConfig());
            String type = rule.getType();
            String name = rule.getName();
            String key = null;
            Rule ruleInstance;
            CleanPlan cleanPlan;
            if (type.equalsIgnoreCase("udf")) {
                Class udfClass = CommonTools.loadClass(name);
                if (!Rule.class.isAssignableFrom(udfClass)) {
                    throw new InvalidRuleException("The specified class is not a Rule class.");
                }

                ruleInstance = (Rule) udfClass.newInstance();
                ruleInstance.initialize(rule.getName(), tables);
                cleanPlan = new CleanPlan(config, ruleInstance);
                key = scheduler.submitDetectJob(cleanPlan);
            } else {
                // TODO: declarative rule only supports 1 table
                Collection<Rule> rules =
                    buildAbstractRule(rule, table1);
                for (Rule rule_ : rules) {
                    rule_.initialize(rule.getName(), tables);
                    cleanPlan = new CleanPlan(config, rule_);
                    key = scheduler.submitDetectJob(cleanPlan);
                }
            }

            return key;

        } catch (InvalidRuleException ex) {
            tracer.err("Exception in detect", ex);
            TNadeefRemoteException re = new TNadeefRemoteException();
            re.setType(TNadeefExceptionType.INVALID_RULE);
            re.setMessage(ex.getMessage());
            throw re;
        } catch (Exception ex) {
            tracer.err("Exception in detect", ex);
            TNadeefRemoteException re = new TNadeefRemoteException();
            re.setType(TNadeefExceptionType.UNKNOWN);
            re.setMessage(ex.getMessage());
            throw re;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String repair(TRule rule, String table1, String table2) throws TNadeefRemoteException {
        if (!verify(rule)) {
            TNadeefRemoteException ex = new TNadeefRemoteException();
            ex.setType(TNadeefExceptionType.COMPILE_ERROR);
            throw ex;
        }

        List<String> tables = Lists.newArrayList();
        tables.add(table1);
        if (table2 != null && !table2.isEmpty()) {
            tables.add(table2);
        }

        try {
            String name = rule.getName();
            Class udfClass = CommonTools.loadClass(name);
            if (!Rule.class.isAssignableFrom(udfClass)) {
                throw new InvalidRuleException("The specified class is not a Rule class.");
            }

            Rule ruleInstance = (Rule) udfClass.newInstance();
            ruleInstance.initialize(rule.getName(), tables);
            DBConfig config = new DBConfig(NadeefConfiguration.getDbConfig());

            NadeefJobScheduler scheduler = NadeefJobScheduler.getInstance();
            String key = scheduler.submitRepairJob(new CleanPlan(config, ruleInstance));
            return key;
        } catch (InvalidRuleException ex) {
            tracer.err("Exception in detect", ex);
            TNadeefRemoteException re = new TNadeefRemoteException();
            re.setType(TNadeefExceptionType.INVALID_RULE);
            re.setMessage(ex.getMessage());
            throw re;
        } catch (Exception ex) {
            tracer.err("Exception in detect", ex);
            TNadeefRemoteException re = new TNadeefRemoteException();
            re.setType(TNadeefExceptionType.UNKNOWN);
            re.setMessage(ex.getMessage());
            throw re;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TJobStatus getJobStatus(String key) throws TException {
        NadeefJobScheduler jobScheduler = NadeefJobScheduler.getInstance();
        return jobScheduler.getJobStatus(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TJobStatus> getAllJobStatus() throws TException {
        NadeefJobScheduler jobScheduler = NadeefJobScheduler.getInstance();
        return jobScheduler.getJobStatus();
    }

    private Collection<Rule> buildAbstractRule(TRule tRule, String tableName) throws Exception {
        String type = tRule.getType();
        String name = tRule.getName();
        String code = tRule.getCode();

        List<String> lines = Lists.newArrayList(code.split("\n"));

        RuleBuilder ruleBuilder;
        Collection<Rule> result;
        ruleBuilder = NadeefConfiguration.tryGetRuleBuilder(type);
        Schema schema = DBMetaDataTool.getSchema(NadeefConfiguration.getDbConfig(), tableName);
        if (ruleBuilder != null) {
            result = ruleBuilder
                .name(name)
                .schema(schema)
                .table(tableName)
                .value(lines)
                .build();
        } else {
            tracer.err("Unknown Rule type: " + type, null);
            throw new IllegalArgumentException("Unknown rule type");
        }
        return result;
    }
}
