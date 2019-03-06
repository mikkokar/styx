import React, { Component } from 'react';
//import Data from './Data';
//import Filter from './Filter'
import './App.css';

class App extends Component {
  constructor(props) {
    super(props);
   this.state={ shopping:'', 
                landing:'',
                filter:''}

  }

  submit = (userInput)=>{
  var url ='/admin/origins/status?pretty'
  
  fetch(url)
   .then(response => response.json())
   .then((data)=>{console.log(data.landing.activeOrigins[0].id); this.setState({shopping:data.landing.activeOrigins[0].id,"Landing-App":data.Landing-App})}) }

    //origins = (e)=>{this.setState({filter:e})}
	

  render() {
	  
    return (
      <div className="App">
		   <div className='header'><h2>Styx Origins Dashboard</h2></div>
		   <button onClick={this.submit}>click</button>
		   {this.state.shopping}
	  </div>
	
	);
	

  }
}



export default App;
