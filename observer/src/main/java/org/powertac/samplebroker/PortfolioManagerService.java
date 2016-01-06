/*
 * Copyright (c) 2012-2013 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.samplebroker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
//io
import java.io.*;
import java.util.Collections;

import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.Broker;
import org.powertac.common.Competition;
import org.powertac.common.CustomerInfo;
import org.powertac.common.Rate;
import org.powertac.common.RegulationRate;
import org.powertac.common.TariffSpecification;
import org.powertac.common.TariffTransaction;
import org.powertac.common.TimeService;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.msg.BalancingControlEvent;
import org.powertac.common.msg.BalancingOrder;
import org.powertac.common.msg.CustomerBootstrapData;
import org.powertac.common.msg.TariffRevoke;
import org.powertac.common.msg.TariffStatus;
import org.powertac.common.repo.CustomerRepo;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.samplebroker.core.BrokerPropertiesService;
import org.powertac.samplebroker.interfaces.Activatable;
import org.powertac.samplebroker.interfaces.BrokerContext;
import org.powertac.samplebroker.interfaces.Initializable;
import org.powertac.samplebroker.interfaces.MarketManager;
import org.powertac.samplebroker.interfaces.PortfolioManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Handles portfolio-management responsibilities for the broker. This
 * includes composing and offering tariffs, keeping track of customers and their
 * usage, monitoring tariff offerings from competing brokers.
 * 
 * A more complete broker implementation might split this class into two or
 * more classes; the keys are to decide which messages each class handles,
 * what each class does on the activate() method, and what data needs to be
 * managed and shared.
 * 
 * @author John Collins
 */
@Service // Spring creates a single instance at startup
public class PortfolioManagerService 
implements PortfolioManager, Initializable, Activatable
{
  static private Logger log = Logger.getLogger(PortfolioManagerService.class);
  
  private BrokerContext brokerContext; // master

  // Spring fills in Autowired dependencies through a naming convention
  @Autowired
  private BrokerPropertiesService propertiesService;

  @Autowired
  private TimeslotRepo timeslotRepo;

  @Autowired
  private TariffRepo tariffRepo;

  @Autowired
  private CustomerRepo customerRepo;

  @Autowired
  private MarketManager marketManager;

  @Autowired
  private TimeService timeService;

  // ---- Portfolio records -----
  // Customer records indexed by power type and by tariff. Note that the
  // CustomerRecord instances are NOT shared between these structures, because
  // we need to keep track of subscriptions by tariff.
  private HashMap<PowerType,
                  HashMap<CustomerInfo, CustomerRecord>> customerProfiles;
  private HashMap<TariffSpecification, 
                  HashMap<CustomerInfo, CustomerRecord>> customerSubscriptions;
  private HashMap<PowerType, List<TariffSpecification>> competingTariffs;

  // Configurable parameters for tariff composition
  // Override defaults in src/main/resources/config/broker.config
  // or in top-level config file
  @ConfigurableValue(valueType = "Double",
          description = "target profit margin")
  private double defaultMargin = 0.1;

  @ConfigurableValue(valueType = "Double",
          description = "Fixed cost/kWh")
  private double fixedPerKwh = -0.2;

  @ConfigurableValue(valueType = "Double",
          description = "Default daily meter charge")
  private double defaultPeriodicPayment = 0;
  
  @ConfigurableValue(valueType = "Double",
          description = "signup payment")
  private double signupPayment = 0;  
  
  @ConfigurableValue(valueType = "Double",
          description = "early withdraw payment")
  private double earlyWithdrawPayment = 0;  

  @ConfigurableValue(valueType = "Double",
          description = "min duration")
  private double minDuration = 0;

   @ConfigurableValue(valueType = "Double",
          description = "lower bound")
  private double lowerbound = 0;
  
  

  /**
   * Default constructor registers for messages, must be called after 
   * message router is available.
   */
  public PortfolioManagerService ()
  {
    super();
  }

  /**
   * Per-game initialization. Configures parameters and registers
   * message handlers.
   */
  @Override // from Initializable
//  @SuppressWarnings("unchecked")
  public void initialize (BrokerContext context)
  {
    this.brokerContext = context;
    propertiesService.configureMe(this);
    customerProfiles = new HashMap<PowerType,
        HashMap<CustomerInfo, CustomerRecord>>();
    customerSubscriptions = new HashMap<TariffSpecification,
        HashMap<CustomerInfo, CustomerRecord>>();
    competingTariffs = new HashMap<PowerType, List<TariffSpecification>>();
  }
  
  // -------------- data access ------------------
  
  /**
   * Returns the CustomerRecord for the given type and customer, creating it
   * if necessary.
   */
  CustomerRecord getCustomerRecordByPowerType (PowerType type,
                                               CustomerInfo customer)
  {
    HashMap<CustomerInfo, CustomerRecord> customerMap =
        customerProfiles.get(type);
    if (customerMap == null) {
      customerMap = new HashMap<CustomerInfo, CustomerRecord>();
      customerProfiles.put(type, customerMap);
    }
    CustomerRecord record = customerMap.get(customer);
    if (record == null) {
      record = new CustomerRecord(customer);
      customerMap.put(customer, record);
    }
    return record;
  }
  
  /**
   * Returns the customer record for the given tariff spec and customer,
   * creating it if necessary. 
   */
  CustomerRecord getCustomerRecordByTariff (TariffSpecification spec,
                                            CustomerInfo customer)
  {
    HashMap<CustomerInfo, CustomerRecord> customerMap =
        customerSubscriptions.get(spec);
    if (customerMap == null) {
      customerMap = new HashMap<CustomerInfo, CustomerRecord>();
      customerSubscriptions.put(spec, customerMap);
    }
    CustomerRecord record = customerMap.get(customer);
    if (record == null) {
      // seed with the generic record for this customer
      record =
          new CustomerRecord(getCustomerRecordByPowerType(spec.getPowerType(),
                                                          customer));
      customerMap.put(customer, record);
    }
    return record;
  }
  
  /**
   * Finds the list of competing tariffs for the given PowerType.
   */
  List<TariffSpecification> getCompetingTariffs (PowerType powerType)
  {
    List<TariffSpecification> result = competingTariffs.get(powerType);
    if (result == null) {
      result = new ArrayList<TariffSpecification>();
      competingTariffs.put(powerType, result);
    }
    return result;
  }

  /**
   * Adds a new competing tariff to the list.
   */
  private void addCompetingTariff (TariffSpecification spec)
  {
    getCompetingTariffs(spec.getPowerType()).add(spec);
  }

  /**
   * Returns total usage for a given timeslot (represented as a simple index).
   */
  @Override
  public double collectUsage (int index)
  {
    double result = 0.0;
    for (HashMap<CustomerInfo, CustomerRecord> customerMap : customerSubscriptions.values()) {
      for (CustomerRecord record : customerMap.values()) {
        result += record.getUsage(index);
      }
    }
    return -result; // convert to needed energy account balance
  }

  // -------------- Message handlers -------------------
  /**
   * Handles CustomerBootstrapData by populating the customer model 
   * corresponding to the given customer and power type. This gives the
   * broker a running start.
   */
  public void handleMessage (CustomerBootstrapData cbd)
  {
    CustomerInfo customer =
            customerRepo.findByNameAndPowerType(cbd.getCustomerName(),
                                                cbd.getPowerType());
    CustomerRecord record = getCustomerRecordByPowerType(cbd.getPowerType(), customer);
    int offset = (timeslotRepo.currentTimeslot().getSerialNumber()
                  - cbd.getNetUsage().length);
    int subs = record.subscribedPopulation;
    record.subscribedPopulation = customer.getPopulation();
    for (int i = 0; i < cbd.getNetUsage().length; i++) {
      record.produceConsume(cbd.getNetUsage()[i], i);
    }
    record.subscribedPopulation = subs;
  }

  /**
   * Handles a TariffSpecification. These are sent by the server when new tariffs are
   * published. If it's not ours, then it's a competitor's tariff. We keep track of 
   * competing tariffs locally, and we also store them in the tariffRepo.
   */
   private int tariff_count = 0;
  public synchronized void handleMessage (TariffSpecification spec)
  {
	tariff_count++;
    Broker theBroker = spec.getBroker();
    if (brokerContext.getBrokerUsername().equals(theBroker.getUsername())) {
      if (theBroker != brokerContext.getBroker())
        // strange bug, seems harmless for now
        log.info("Resolution failed for broker " + theBroker.getUsername());
      // if it's ours, just log it, because we already put it in the repo
      TariffSpecification original =
              tariffRepo.findSpecificationById(spec.getId());
      if (null == original)
        log.error("Spec " + spec.getId() + " not in local repo");
      log.info("published " + spec);
    }
    else {
      // otherwise, keep track of competing tariffs, and record in the repo
      addCompetingTariff(spec);
      tariffRepo.addSpecification(spec);
    }
  }
  
  /**
   * Handles a TariffStatus message. This should do something when the status
   * is not SUCCESS.
   */
  public synchronized void handleMessage (TariffStatus ts)
  {
    log.info("TariffStatus: " + ts.getStatus());
  }
  
  /**
   * Handles a TariffTransaction. We only care about certain types: PRODUCE,
   * CONSUME, SIGNUP, and WITHDRAW.
   */
   
  private int signupCount = 0;
  private int consumeCount = 0;
  private int withdrawCount = 0;
  public synchronized void handleMessage(TariffTransaction ttx)
  {
    // make sure we have this tariff
	//System.out.println("Transaction come");
    TariffSpecification newSpec = ttx.getTariffSpec();
	//Broker source = newSpec.getBroker();
	//System.out.println("From: "+source.getUsername());
    if (newSpec == null) {
      log.error("TariffTransaction type=" + ttx.getTxType()
                + " for unknown spec");
    }
    else {
      TariffSpecification oldSpec =
              tariffRepo.findSpecificationById(newSpec.getId());
      if (oldSpec != newSpec) {
        log.error("Incoming spec " + newSpec.getId() + " not matched in repo");
      }
    }
    TariffTransaction.Type txType = ttx.getTxType();
    CustomerRecord record = getCustomerRecordByTariff(ttx.getTariffSpec(),
                                                      ttx.getCustomerInfo());
    
    if (TariffTransaction.Type.SIGNUP == txType) {
      // keep track of customer counts
	  //System.out.println("Signup! customer number: " + ttx.getCustomerCount());
	  //System.out.println("Check subscribed number: " + record.subscribedPopulation);
	  //System.out.println("=====================================");
	
      record.signup(ttx.getCustomerCount());
	  signupCount++;
    }
    else if (TariffTransaction.Type.WITHDRAW == txType) {
      // customers presumably found a better deal
	  //System.out.println("Quit! customer number: " + ttx.getCustomerCount());
	  //System.out.println("Check subscribed number: " + record.subscribedPopulation);
	  //System.out.println("=====================================");
      record.withdraw(ttx.getCustomerCount());
	  withdrawCount++;
    }
    else if (TariffTransaction.Type.PRODUCE == txType) {
      // if ttx count and subscribe population don't match, it will be hard
      // to estimate per-individual production
      if (ttx.getCustomerCount() != record.subscribedPopulation) {
        log.warn("production by subset " + ttx.getCustomerCount() +
                 " of subscribed population " + record.subscribedPopulation);
      }
      record.produceConsume(ttx.getKWh(), ttx.getPostedTime());
    }
    else if (TariffTransaction.Type.CONSUME == txType) {
      if (ttx.getCustomerCount() != record.subscribedPopulation) {
        log.warn("consumption by subset " + ttx.getCustomerCount() +
                 " of subscribed population " + record.subscribedPopulation);
      }
      record.produceConsume(ttx.getKWh(), ttx.getPostedTime());  
		consumeCount++;
    }
  }

  /**
   * Handles a TariffRevoke message from the server, indicating that some
   * tariff has been revoked.
   */
  public synchronized void handleMessage (TariffRevoke tr)
  {
    Broker source = tr.getBroker();
    log.info("Revoke tariff " + tr.getTariffId()
             + " from " + tr.getBroker().getUsername());
	System.out.println("Revoke tariff " + tr.getTariffId()
             + " from " + tr.getBroker().getUsername());
    // if it's from some other broker, we need to remove it from the
    // tariffRepo, and from the competingTariffs list
    if (!(source.getUsername().equals(brokerContext.getBrokerUsername()))) {
      log.info("clear out competing tariff");
      TariffSpecification original =
              tariffRepo.findSpecificationById(tr.getTariffId());
      if (null == original) {
        log.warn("Original tariff " + tr.getTariffId() + " not found");
        return;
      }
      tariffRepo.removeSpecification(original.getId());
      List<TariffSpecification> candidates =
              competingTariffs.get(original.getPowerType());
      if (null == candidates) {
        log.warn("Candidate list is null");
        return;
      }
      candidates.remove(original);
    }
  }

  /**
   * Handles a BalancingControlEvent, sent when a BalancingOrder is
   * exercised by the DU.
   */
  public synchronized void handleMessage (BalancingControlEvent bce)
  {
    log.info("BalancingControlEvent " + bce.getKwh());
  }

  // --------------- activation -----------------
  /**
   * Called after TimeslotComplete msg received. Note that activation order
   * among modules is non-deterministic.
   */
   private int tariff_creation=0;
   private int dayn = 0;
   private double old_mean = 0.0;
   private double old_min = 0.0;
   private double old_max = 0.0;
   private double my_min=999;
   private double old_mean_signup = 0;
   private double oldCashPos=0;
   private double CashPos=0;
   private double pubfee=0;
   private boolean pubFlag = false;
   private int interest = 3;
   private boolean fwflag = true;
   private FileWriter fw = null;

  @Override // from Activatable
  public synchronized void activate (int timeslotIndex)
  {
  
	
	System.out.println("timeslot is: " + timeslotIndex);
	System.out.println("openfile flag: " + fwflag);
	if(fwflag){
	try{
	fw = new FileWriter("stat.txt");
	fwflag = false;
	}
	catch(IOException e){
	}}	
	
	methods m = new methods();
	Broker me = brokerContext.getBroker();
	CashPos = me.getCashBalance();
	if(pubFlag){
		pubfee = oldCashPos - CashPos;
		pubFlag = false;
		System.out.println("Publication fee: " + pubfee);
	}
	//fixedRateList.add(2.0);
	//TariffSpecification contariff = null;
    if (customerSubscriptions.size() == 0) {
      // we (most likely) have no tariffs
      //createInitialTariffs();
	  pubFlag = true;
    }

	if (timeslotIndex%6 == 0&&timeslotIndex>360){
	double mean_fixed = 0;
    double max_rate = 0;
    double min_rate = 0;	
	double sd_fixed = 0;
	double mean_signup = 0;
	double max_signup = 0;
	double min_signup = 0;
	double sd_signup = 0;	
	double rate_publish = 0;
	double diff_mean_signup = 0;
	double diff_mean_fixed = 0;
	double diff_min_fixed = 0;
	double diff_max_fixed = 0;
	List<TariffSpecification> tars = getCompetingTariffs(PowerType.CONSUMPTION);	
      if (null == tars || 0 == tars.size()){
        System.out.println("No tariffs found");		
		}
      else {	
			double ratevalue;
			List<Double> fixedRateList = new ArrayList<Double>();
			List<Double> signupList = new ArrayList<Double>();
			for (TariffSpecification tar: tars) {
			//Brokers
			Broker sourceBroker = tar.getBroker();
			if (!(sourceBroker.getUsername().equals("default broker")))
			{
			
			
			List<Rate> ratev = tar.getRates();
			for (Rate rates: ratev){
			if(rates.isFixed()){
			ratevalue = rates.getValue();
			//System.out.println(ratevalue);
			fixedRateList.add(ratevalue);
			
			}
			}
			signupList.add(tar.getSignupPayment());
			}
			}
			List<TariffSpecification> myspecs = tariffRepo.findTariffSpecificationsByBroker(brokerContext.getBroker());
			if (null == myspecs || 0 == myspecs.size()){
				System.out.println("No tariffs for us found");
				}
			else{
				for(TariffSpecification myspec: myspecs){
					if (PowerType.CONSUMPTION == myspec.getPowerType()) {
						List<Rate> ratev = myspec.getRates();
						for (Rate rates: ratev){
						if(rates.isFixed()){
						ratevalue = rates.getValue();
						System.out.println(ratevalue);
						fixedRateList.add(ratevalue);
						
						}
						}
						signupList.add(myspec.getSignupPayment());
					}
					
				}
			}
			mean_fixed = m.mean(fixedRateList);
			sd_fixed = m.sd(fixedRateList);
			min_rate = Collections.min(fixedRateList);
			max_rate = Collections.max(fixedRateList);
			mean_signup = m.mean(signupList);
			max_signup = Collections.max(signupList);
			min_signup = Collections.min(signupList);
			sd_signup = m.sd(signupList);
			if (old_mean == 0){
			diff_mean_fixed = 0;
			diff_min_fixed = 0;
			diff_max_fixed = 0;
			}
			else{
			diff_mean_fixed = mean_fixed - old_mean;
			diff_max_fixed = max_rate - old_max;
			diff_min_fixed = min_rate - old_min;
			}
			if (old_mean_signup == 0){
			diff_mean_signup = 0;
			}
			else{
			diff_mean_signup = mean_signup - old_mean_signup;
			}			
			old_mean_signup = mean_signup;
			
			//	mean_fixed = (-1)*mean_fixed;
			//	max_rate = (-1)*max_rate;
			//	diff_mean_fixed = (-1)*diff_mean_fixed;
			//	diff_max_fixed = (-1)*diff_max_fixed;
			double a_value = mean_fixed*(-10) + sd_fixed*(-10)+max_rate*(-10)+min_rate*(-10)+diff_mean_fixed*(-10)
						   + mean_signup*(10) + sd_signup*(-10) + max_signup*(10) + min_signup*(10)+diff_mean_signup*(-10)
						   + signupCount + tariff_count;
								
			
			
				System.out.println("Period: " + dayn);
				System.out.println("Mean of fixed rate: " + mean_fixed);
				System.out.println("Max of fixed rate: " + max_rate);
				System.out.println("Min of fixed rate: " + min_rate);
				System.out.println("SD of fixed rate: " + sd_fixed);
				System.out.println("rate of change of mean fixed rate: " + diff_mean_fixed);
				System.out.println("Mean of signup: " + mean_signup);
				System.out.println("Max of signup: " + max_signup);
				System.out.println("Min of signup: " + min_signup);
				System.out.println("SD of signup: " + sd_signup);
				System.out.println("rate of change of mean signup: " + diff_mean_signup);				
				System.out.println("Subscription of customers to Agent: "+ signupCount);
				System.out.println("Number of tariffs publish in 6 timeslot: " + tariff_count );
				System.out.println("Aggressive value: "+ a_value);
			try{
				fw.write("Period: " + dayn);
				fw.write(System.getProperty("line.separator"));
				fw.write("Mean of fixed rate: " + mean_fixed);
				fw.write(System.getProperty("line.separator"));
				fw.write("Max of fixed rate: " + max_rate);
				fw.write(System.getProperty("line.separator"));
				fw.write("Min of fixed rate: " + min_rate);
				fw.write(System.getProperty("line.separator"));
				fw.write("SD of fixed rate: " + sd_fixed);
				fw.write(System.getProperty("line.separator"));
				fw.write("rate of change of mean fixed rate: " + diff_mean_fixed);
				fw.write(System.getProperty("line.separator"));
				fw.write("Mean of signup: " + mean_signup);
				fw.write(System.getProperty("line.separator"));
				fw.write("Max of signup: " + max_signup);
				fw.write(System.getProperty("line.separator"));
				fw.write("Min of signup: " + min_signup);
				fw.write(System.getProperty("line.separator"));
				fw.write("SD of signup: " + sd_signup);
				fw.write(System.getProperty("line.separator"));
				fw.write("rate of change of mean signup: " + diff_mean_signup);
				fw.write(System.getProperty("line.separator"));
				fw.write("Subscription of customers to Agent: "+ signupCount);
				fw.write(System.getProperty("line.separator"));
				fw.write("Number of tariffs publish in 6 timeslot: " + tariff_count);
				fw.write(System.getProperty("line.separator"));
				fw.write("Aggressive value: "+ a_value);
				fw.write("==========================================");
				fw.write(System.getProperty("line.separator"));
			    fw.write("==========================================");
			    fw.write(System.getProperty("line.separator"));				
				fw.flush();
				
				
			}catch(IOException e){
				e.printStackTrace();
			}
		
		
		}
		if(interest>0 && (my_min < max_rate)) //max should be lowest price
		{	
			createTariffs((max_rate+mean_fixed)/2);
			interest--;
		}
		else if(signupCount == 0 || consumeCount == 0)
		{
			//create; 			//no one buy!!!! suck!!!!
			System.out.println("case 2 publish, no one buy energy");
			createTariffs(max_rate);
		}
		else if((CashPos-oldCashPos) > pubfee)
		{
			//create;
			System.out.println("case 3 publish");
			createTariffs(mean_fixed);
		}
		
		dayn++;
		tariff_count = 0;
		signupCount = 0;
		consumeCount = 0;
		withdrawCount = 0;
		oldCashPos = CashPos;
	}
	
  }
	private void createTariffs (double minRate)
	  {
		//System.out.println("minRate: " + minRate);
		double rateValue = minRate;
		boolean getp = true;
		//while(getp)
		//{
		rateValue = minRate * (1-Math.random()*0.01);
		if (rateValue > -0.065)
		{
		//getp = false;
		rateValue = -0.065;
		}
		//}
		my_min = rateValue;	
		TariffSpecification spec =
		new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION)
			.withMinDuration(256000000)
			.withSignupPayment(signupPayment)
			.withEarlyWithdrawPayment(earlyWithdrawPayment);	
		Rate rate = new Rate().withValue(rateValue);
		spec.addRate(rate);
		customerSubscriptions.put(spec, new HashMap<CustomerInfo, CustomerRecord>());
		tariffRepo.addSpecification(spec);
		// = me.getCashBalance();
		brokerContext.sendMessage(spec);
	  }  
  
  
  
  
class methods{

public double sum(List<Double> a){
	if(a.size() > 0){
	double sum = 0;
	for(Double i: a){
	sum = sum + i;
	}
	return sum;
	}
	return 0;
}
public double mean (List<Double> a){
	double sum = sum(a);
	double mean = 0;
	if (a.size() != 0){
	mean = sum / (a.size() * 1.0);
	return mean;
	}
	else
	return 0;
}
public double sd (List<Double> a){
    double sum = 0;
    double mean = mean(a);
 
    for (double i : a)
        sum += Math.pow((i - mean), 2);
    return Math.sqrt( sum / ( a.size() - 1 ) ); 
    }
}
 
  // Creates initial tariffs for the main power types. These are simple
  // fixed-rate two-part tariffs that give the broker a fixed margin.
  private void createInitialTariffs ()
  {
    // remember that market prices are per mwh, but tariffs are by kwh
    double marketPrice = marketManager.getMeanMarketPrice() / 1000.0;
	marketPrice = 0;
	//System.out.println("marketPrice = "+marketPrice);
    // for each power type representing a customer population,
    // create a tariff that's better than what's available
	double rateValue;
	rateValue = ((marketPrice + fixedPerKwh) * (1.0 + defaultMargin) * (1-Math.random()*0.1));
	my_min = rateValue;	
	TariffSpecification spec =
    new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION)
		.withMinDuration(256000000)
		.withSignupPayment(signupPayment)
		.withEarlyWithdrawPayment(earlyWithdrawPayment);	
    Rate rate = new Rate().withValue(rateValue);
    spec.addRate(rate);
    customerSubscriptions.put(spec, new HashMap<CustomerInfo, CustomerRecord>());
    tariffRepo.addSpecification(spec);
	// = me.getCashBalance();
    brokerContext.sendMessage(spec);
	
		//production
		rateValue = -0.5 * marketPrice;
		TariffSpecification spec2 =
        new TariffSpecification(brokerContext.getBroker(), PowerType.PRODUCTION)
		.withMinDuration(256000000)
		.withSignupPayment(signupPayment)
		.withEarlyWithdrawPayment(earlyWithdrawPayment);	
		rate = new Rate().withValue(rateValue);
		spec2.addRate(rate);
		customerSubscriptions.put(spec2, new HashMap<CustomerInfo, CustomerRecord>());
		tariffRepo.addSpecification(spec2);
		brokerContext.sendMessage(spec2);	
	
	
	/**
    for (PowerType pt : customerProfiles.keySet()) {
      // we'll just do fixed-rate tariffs for now
	//System.out.println(pt);
      
		if (pt.isProduction()){
		rateValue = -2.0 * marketPrice * (1-Math.random()*0.1);	
		TariffSpecification spec2 =
        new TariffSpecification(brokerContext.getBroker(), PowerType.PRODUCTION)
		.withMinDuration(256000000)
		.withSignupPayment(signupPayment)
		.withEarlyWithdrawPayment(earlyWithdrawPayment);	
      rate = new Rate().withValue(rateValue);
      spec2.addRate(rate);
      customerSubscriptions.put(spec2, new HashMap<CustomerInfo, CustomerRecord>());
      tariffRepo.addSpecification(spec2);
      brokerContext.sendMessage(spec2);
		break;
		}
		
    }
	**/
  }

  // Checks to see whether our tariffs need fine-tuning
  private void improveTariffs()
  {
    // quick magic-number hack to inject a balancing order
    int timeslotIndex = timeslotRepo.currentTimeslot().getSerialNumber();
    if (371 == timeslotIndex) {
      for (TariffSpecification spec :
           tariffRepo.findTariffSpecificationsByBroker(brokerContext.getBroker())) {
        if (PowerType.INTERRUPTIBLE_CONSUMPTION == spec.getPowerType()) {
          BalancingOrder order = new BalancingOrder(brokerContext.getBroker(),
                                                    spec, 
                                                    0.5,
                                                    spec.getRates().get(0).getMinValue() * 0.9);
          brokerContext.sendMessage(order);
        }
        else if (spec.hasRegulationRate()) {
          // supports both up-regulation and down-regulation
          RegulationRate rr = spec.getRegulationRates().get(0);
          double up = -rr.getUpRegulationPayment();
          double down = -rr.getDownRegulationPayment();
          BalancingOrder bup = new BalancingOrder(brokerContext.getBroker(),
                                                 spec, 1.0, up * 0.5);
          BalancingOrder bdown = new BalancingOrder(brokerContext.getBroker(),
                                                    spec, -1.0, down * 0.9);
          brokerContext.sendMessage(bup);
          brokerContext.sendMessage(bdown);
        }
      }
    }
    // magic-number hack to supersede a tariff
    if (380 == timeslotIndex) {
      // find the existing CONSUMPTION tariff
      TariffSpecification oldc = null;
      List<TariffSpecification> candidates =
        tariffRepo.findTariffSpecificationsByBroker(brokerContext.getBroker());
      if (null == candidates || 0 == candidates.size())
        log.error("No tariffs found for broker");
      else {
        // oldc = candidates.get(0);
        for (TariffSpecification candidate: candidates) {
          if (candidate.getPowerType() == PowerType.CONSUMPTION) {
            oldc = candidate;
            break;
          }
        }
        if (null == oldc) {
          log.warn("No CONSUMPTION tariffs found");
        }
        else {
          double rateValue = oldc.getRates().get(0).getValue();
          // create a new CONSUMPTION tariff
          TariffSpecification spec =
            new TariffSpecification(brokerContext.getBroker(),
                                    PowerType.CONSUMPTION);//.withMinDuration(2560000).withEarlyWithdrawPayment(0.1);
                //.withPeriodicPayment(defaultPeriodicPayment * 1.1);
          Rate rate = new Rate().withValue(rateValue);
          spec.addRate(rate);
          if (null != oldc)
            spec.addSupersedes(oldc.getId());
          //mungId(spec, 6);
          tariffRepo.addSpecification(spec);
          brokerContext.sendMessage(spec);
          // revoke the old one
          TariffRevoke revoke =
            new TariffRevoke(brokerContext.getBroker(), oldc);
          brokerContext.sendMessage(revoke);
        }
      }
    }
  }

  // ------------- test-support methods ----------------
  double getUsageForCustomer (CustomerInfo customer,
                              TariffSpecification tariffSpec,
                              int index)
  {
    CustomerRecord record = getCustomerRecordByTariff(tariffSpec, customer);
    return record.getUsage(index);
  }
  
  // test-support method
  HashMap<PowerType, double[]> getRawUsageForCustomer (CustomerInfo customer)
  {
    HashMap<PowerType, double[]> result = new HashMap<PowerType, double[]>();
    for (PowerType type : customerProfiles.keySet()) {
      CustomerRecord record = customerProfiles.get(type).get(customer);
      if (record != null) {
        result.put(type, record.usage);
      }
    }
    return result;
  }

  // test-support method
  HashMap<String, Integer> getCustomerCounts()
  {
    HashMap<String, Integer> result = new HashMap<String, Integer>();
    for (TariffSpecification spec : customerSubscriptions.keySet()) {
      HashMap<CustomerInfo, CustomerRecord> customerMap = customerSubscriptions.get(spec);
      for (CustomerRecord record : customerMap.values()) {
        result.put(record.customer.getName() + spec.getPowerType(), 
                    record.subscribedPopulation);
      }
    }
    return result;
  }

  // code to test id-prefix checking
//  private void mungId (TariffSpecification spec, int i)
//  {
//    long id = spec.getId();
//    long baseId =
//      id - IdGenerator.extractPrefix(id) * IdGenerator.getMultiplier();
//    Field idField = findIdField(spec.getClass());
//    try {
//      idField.setAccessible(true);
//      idField.setLong(spec, baseId + i * IdGenerator.getMultiplier());
//    }
//    catch (Exception e) {
//      log.error(e.toString());
//    }
//  }

  // finds a field in superclass hierarchy
//  private Field findIdField (Class<?> clazz)
//  {
//    try {
//      Field idField = clazz.getDeclaredField("id");
//      return idField;
//    }
//    catch (NoSuchFieldException e) {
//      Class<?> superclass = clazz.getSuperclass();
//      if (null == superclass) {
//        return null;
//      }
//      return findIdField(superclass);
//    }
//    catch (SecurityException e) {
//      // Auto-generated catch block
//      e.printStackTrace();
//      return null;
//    }
//  }

  //-------------------- Customer-model recording ---------------------
  /**
   * Keeps track of customer status and usage. Usage is stored
   * per-customer-unit, but reported as the product of the per-customer
   * quantity and the subscribed population. This allows the broker to use
   * historical usage data as the subscribed population shifts.
   */
  class CustomerRecord
  {
    CustomerInfo customer;
    int subscribedPopulation = 0;
    double[] usage;
    double alpha = 0.3;
    
    /**
     * Creates an empty record
     */
    CustomerRecord (CustomerInfo customer)
    {
      super();
      this.customer = customer;
      this.usage = new double[brokerContext.getUsageRecordLength()];
    }
    
    CustomerRecord (CustomerRecord oldRecord)
    {
      super();
      this.customer = oldRecord.customer;
      this.usage = Arrays.copyOf(oldRecord.usage, brokerContext.getUsageRecordLength());
    }
    
    // Returns the CustomerInfo for this record
    CustomerInfo getCustomerInfo ()
    {
      return customer;
    }
    
    // Adds new individuals to the count
    void signup (int population)
    {
      subscribedPopulation = Math.min(customer.getPopulation(),
                                      subscribedPopulation + population);
    }
    
    // Removes individuals from the count
    void withdraw (int population)
    {
      subscribedPopulation -= population;
    }
    
    // Customer produces or consumes power. We assume the kwh value is negative
    // for production, positive for consumption
    void produceConsume (double kwh, Instant when)
    {
      int index = getIndex(when);
      produceConsume(kwh, index);
    }
    
    // store profile data at the given index
    void produceConsume (double kwh, int rawIndex)
    {
      int index = getIndex(rawIndex);
      double kwhPerCustomer = 0.0;
      if (subscribedPopulation > 0) {
        kwhPerCustomer = kwh / (double)subscribedPopulation;
      }
      double oldUsage = usage[index];
      if (oldUsage == 0.0) {
        // assume this is the first time
        usage[index] = kwhPerCustomer;
      }
      else {
        // exponential smoothing
        usage[index] = alpha * kwhPerCustomer + (1.0 - alpha) * oldUsage;
      }
      log.debug("consume " + kwh + " at " + index +
                ", customer " + customer.getName());
    }
    
    double getUsage (int index)
    {
      if (index < 0) {
        log.warn("usage requested for negative index " + index);
        index = 0;
      }
      return (usage[getIndex(index)] * (double)subscribedPopulation);
    }
    
    // we assume here that timeslot index always matches the number of
    // timeslots that have passed since the beginning of the simulation.
    int getIndex (Instant when)
    {
      int result = (int)((when.getMillis() - timeService.getBase()) /
                         (Competition.currentCompetition().getTimeslotDuration()));
      return result;
    }
    
    private int getIndex (int rawIndex)
    {
      return rawIndex % usage.length;
    }
  }
}
