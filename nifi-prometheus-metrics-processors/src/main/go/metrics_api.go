package main;

import (
    "fmt"
)
import "context"
import "github.com/go-kit/kit/log"
import "github.com/go-kit/kit/log/level"
import "gopkg.in/yaml.v2"
import "github.com/prometheus/prometheus/config"
import sd_config "github.com/prometheus/prometheus/discovery/config"
import "github.com/prometheus/prometheus/discovery"
import "github.com/prometheus/prometheus/scrape"
import "github.com/prometheus/prometheus/storage"
import "github.com/prometheus/prometheus/pkg/labels"

type appender struct {
    metricChannel chan string
    logger log.Logger
}
func (a appender) Add(l labels.Labels, t int64, v float64) (uint64, error) {
    a.metricChannel <- fmt.Sprintf("%s %d %f", l, t, v)
    return 1, nil
}
func (a appender) AddFast(l labels.Labels, ref uint64, t int64, v float64) error {
    a.metricChannel <- fmt.Sprintf("%s %d %f", l, t, v)
    return nil
}
func (a appender) Commit() error { 
    return nil 
}
func (a appender) Rollback() error { 
    return nil 
}
type appenderFactory struct{
    metricChannel chan string
    logger log.Logger
}
func (f appenderFactory) Appender() (storage.Appender, error) {
    return appender{f.metricChannel, f.logger}, nil
}
    
type GetMetricsCancel func()
func GetMetricsWithCancel(configStr string, metricChannel chan string, logger log.Logger) GetMetricsCancel {

    level.Info(logger).Log("Starting Discovery")
    cfg := &config.Config{}

	if err := yaml.UnmarshalStrict([]byte(configStr), cfg); err != nil {
        level.Error(logger).Log("Unable to load YAML config configStr: %s", err)
        close(metricChannel)
        return func(){};
    }
    
	ctx, cancelDiscovery := context.WithCancel(context.Background())
    discoveryManager := discovery.NewManager(ctx, log.With(logger, "component", "discovery manager"), discovery.Name("scrape"))
    
	c := make(map[string]sd_config.ServiceDiscoveryConfig)
	for _, v := range cfg.ScrapeConfigs {
		c[v.JobName] = v.ServiceDiscoveryConfig
	}
    if err := discoveryManager.ApplyConfig(c); err != nil {
        level.Error(logger).Log("Discovery Config Failed:" + err.Error())
    }
    go func(){
        if err := discoveryManager.Run(); err != nil {
            level.Error(logger).Log("Discovery Failed:" + err.Error())
        }
    }()
    
    level.Info(logger).Log("Discovery started")

    scrapeManager := scrape.NewManager(log.With(logger, "component", "scrape manager"), appenderFactory{metricChannel, logger})
    if err := scrapeManager.ApplyConfig(cfg); err != nil {
        level.Error(logger).Log("Scraping Config Failed:" + err.Error())
    }
    go func(){
        if err := scrapeManager.Run(discoveryManager.SyncCh()); err != nil {
            level.Error(logger).Log("Scraping Failed:" + err.Error())
        }
    }()
    level.Info(logger).Log("Scraping Started")

    return func (){
        scrapeManager.Stop()
        cancelDiscovery()
    }
}