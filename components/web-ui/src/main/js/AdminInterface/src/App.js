import React, { Component } from 'react';
import Data from './Data';
import Filter from './Filter'

import './App.css';

class App extends Component {
  constructor(props) {
    super(props);
		this.state={ response:{
      "shopping" : {
        "appId" : "shopping",
        "activeOrigins" : [ {
          "id" : "shopping-01xx",
          "host" : "origins-server:8083"
        }, {
          "id" : "shopping-02",
          "host" : "origins-server:8084"
        }, {
          "id" : "shopping-03",
          "host" : "origins-server:8085"
        }, {
          "id" : "shopping-04",
          "host" : "origins-server:8086"
        }, {
          "id" : "shopping-05",
          "host" : "origins-server:8087"
        } ],
        "inactiveOrigins" : [ ],
        "disabledOrigins" : [ ]
      },
      "landing" : {
        "appId" : "landing",
        "activeOrigins" : [ {
          "id" : "landing-01",
          "host" : "origins-server:8081"
        }, {
          "id" : "landing-02yy",
          "host" : "origins-server:8082"
        } ],
        "inactiveOrigins" : [ ],
        "disabledOrigins" : [ ]
      },
      "api-server" : {
        "appId" : "api-server",
        "activeOrigins" : [ {
          "id" : "api-server-01",
          "host" : "origins-server:8088"
        } ],
        "inactiveOrigins" : [ ],
        "disabledOrigins" : [ ]
      }
    },
                  current:['shopping','landing'],
                  filter:{active:true,
                    inactive:true,
                    disable:true}
									
                 }

  }
  

   componentDidMount() {
    var url ='/admin/origins/status?pretty'
    
    fetch(url)
    .then(response => response.json())
    .then((data)=>{ this.setState({response:data,current: Object.keys(data),len:this.state.current.length})})  
                        }   
	  
   
    submitted = (filter)=>{this.setState({filter})}//filter 


disable = (appId, originId)=>{ var url =`/admin/tasks/origins?cmd=disable_origin&appId=${appId}&originId=${originId}`
              fetch(url,{
                method:'post'
                
              })
              .then(response => response.json(originId))
               .then((data)=>{ this.setState({response:data,current: Object.keys(data),len:this.state.current.length})}) 
              
              }    

enable = (appId, originId)=>{   var url =`/admin/tasks/origins?cmd=enable_origin&appId=${appId}&originId=${originId}`
              fetch(url,{
                method:'post'              
              })
              .then(response => response.json(originId))
               .then((data)=>{ this.setState({response:data,current: Object.keys(data),len:this.state.current.length})}) 
              
              }

  render() {
    
	 
    return (
      <div className="App">
        <div className='header'><h3>Styx Origins Dashboard</h3></div>
            <Filter submit={selection =>this.submitted(selection)}/>
            <Data num={this.state.response} current={this.state.current} dis={this.disable} enable={this.enable} filter={this.state.filter}/>
              
		  </div>
	  );
		} 
}
export default App;
